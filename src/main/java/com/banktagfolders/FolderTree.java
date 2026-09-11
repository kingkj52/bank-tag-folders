package com.banktagfolders;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * The folder structure laid over the core plugin's flat tag list, plus its
 * persistence.
 * <p>
 * The core tag list stays the single source of truth for which tags exist; this
 * class only records how they are <em>grouped</em>. That split is what makes the
 * plugin non-destructive: every mutation here writes to
 * {@value #GROUP}, never to {@code banktags}.
 */
@Slf4j
@Singleton
public class FolderTree
{
	static final String GROUP = "banktagfolders";

	static final String KEY_FOLDERS = "folders";
	static final String KEY_ROOTS = "roots";

	/** Root entries are tagged so a folder id can never be read as a tag name. */
	private static final String TAG = "t:";
	private static final String FOLDER = "f:";

	/**
	 * Default folder colours, mid-dark so the game's white and orange text stays
	 * readable on top. A new folder takes the first one not already in use.
	 */
	private static final int[] PALETTE = {
		0x3A6B8B, 0x4A7A4A, 0x8B3A3A, 0x6B4A8B,
		0x8B7A3A, 0x3A8B7A, 0x8B5A3A, 0x7A3A6B,
	};

	private final ConfigManager configManager;

	/**
	 * The client's Gson, injected rather than constructed. Building a fresh
	 * instance is rejected by the Plugin Hub, and it would also mean this
	 * plugin quietly stops matching the client's own serialisation settings if
	 * those ever change.
	 * <p>
	 * Everything is read and written as a concrete array type rather than via
	 * Gson's {@code TypeToken}. TypeToken resolves a generic type at runtime
	 * through {@code java.lang.reflect.Type}, and the Plugin Hub does not permit
	 * reflection; an array carries its element type in the class itself.
	 */
	private final Gson gson;

	/** Folder id to folder, insertion ordered so saves round-trip stably. */
	private final Map<String, Folder> folders = new LinkedHashMap<>();

	/** Top level in display order; each entry is {@code t:<tag>} or {@code f:<id>}. */
	private final List<String> roots = new ArrayList<>();

	@Inject
	FolderTree(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	// ------------------------------------------------------------------
	// Persistence
	// ------------------------------------------------------------------

	void load()
	{
		folders.clear();
		roots.clear();

		Folder[] stored = read(KEY_FOLDERS, Folder[].class);
		if (stored != null)
		{
			for (Folder folder : stored)
			{
				// A null id would make the folder unaddressable and every row
				// pointing at it dead, so drop it rather than render a ghost.
				if (folder != null && folder.getId() != null)
				{
					if (folder.getTags() == null)
					{
						folder.setTags(new ArrayList<>());
					}
					if (folder.getColor() == 0)
					{
						folder.setColor(nextColor());
					}
					folders.put(folder.getId(), folder);
				}
			}
		}

		String[] storedRoots = read(KEY_ROOTS, String[].class);
		if (storedRoots != null)
		{
			Collections.addAll(roots, storedRoots);
		}
	}

	@Nullable
	private <T> T read(String key, Class<T> type)
	{
		String json = configManager.getConfiguration(GROUP, key);
		if (json == null || json.isEmpty())
		{
			return null;
		}
		try
		{
			return gson.fromJson(json, type);
		}
		catch (JsonSyntaxException e)
		{
			// Better to start from an empty structure than to refuse to render
			// the column at all; reconcile() will rebuild it from the live tags.
			log.warn("Discarding unreadable {}.{}", GROUP, key, e);
			return null;
		}
	}

	void save()
	{
		configManager.setConfiguration(GROUP, KEY_FOLDERS, gson.toJson(folders.values().toArray(new Folder[0])));
		configManager.setConfiguration(GROUP, KEY_ROOTS, gson.toJson(roots.toArray(new String[0])));
	}

	// ------------------------------------------------------------------
	// Reconciliation against the core tag list
	// ------------------------------------------------------------------

	/**
	 * Fold the live tag list into the stored structure.
	 * <p>
	 * Runs before every render, because tags can appear and disappear entirely
	 * outside this plugin -- created from the core plugin's own UI, deleted,
	 * renamed, or imported. Anything unknown is adopted at the end of the
	 * column and anything stale is dropped, so the two never drift apart.
	 *
	 * @param liveTags tag names in the order {@code banktags} lists them
	 */
	void reconcile(List<String> liveTags)
	{
		Set<String> live = new LinkedHashSet<>(liveTags);
		boolean dirty = false;

		for (Folder folder : new ArrayList<>(folders.values()))
		{
			boolean lostTags = folder.getTags().retainAll(live);
			if (lostTags)
			{
				dirty = true;
			}

			// "Remove all from a folder to remove the folder", covering the case
			// where the last member was deleted from the bank rather than
			// dragged out. Conditioned on having actually lost something this
			// pass: an empty folder that did not just lose its last tag is a
			// brand new one waiting to be filled, and deleting it here would
			// make "New folder" appear to do nothing.
			if (lostTags && folder.getTags().isEmpty())
			{
				folders.remove(folder.getId());
				dirty = true;
			}
		}

		Set<String> filed = new HashSet<>();
		for (Folder folder : folders.values())
		{
			filed.addAll(folder.getTags());
		}

		Set<String> seen = new HashSet<>();
		for (Iterator<String> it = roots.iterator(); it.hasNext(); )
		{
			String entry = it.next();
			if (!seen.add(entry) || !isLiveRoot(entry, live, filed))
			{
				it.remove();
				dirty = true;
			}
		}

		// Folders and tags the roots list has never seen join the bottom of the
		// column, tags in the order the bank itself lists them.
		for (Folder folder : folders.values())
		{
			if (!roots.contains(FOLDER + folder.getId()))
			{
				roots.add(FOLDER + folder.getId());
				dirty = true;
			}
		}
		for (String tag : liveTags)
		{
			if (!filed.contains(tag) && !roots.contains(TAG + tag))
			{
				roots.add(TAG + tag);
				dirty = true;
			}
		}

		if (dirty)
		{
			save();
		}
	}

	private boolean isLiveRoot(String entry, Set<String> live, Set<String> filed)
	{
		if (entry.startsWith(FOLDER))
		{
			return folders.containsKey(entry.substring(FOLDER.length()));
		}
		if (entry.startsWith(TAG))
		{
			String tag = entry.substring(TAG.length());
			// A tag that has since been filed into a folder must not also keep
			// its top-level slot, or it renders twice.
			return live.contains(tag) && !filed.contains(tag);
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Display
	// ------------------------------------------------------------------

	/** The column, flattened: folder headers with their members under them. */
	List<StripRow> rows()
	{
		List<StripRow> rows = new ArrayList<>();
		for (String entry : roots)
		{
			if (entry.startsWith(TAG))
			{
				rows.add(StripRow.tag(entry.substring(TAG.length()), null));
				continue;
			}

			Folder folder = folders.get(entry.substring(FOLDER.length()));
			if (folder == null)
			{
				continue;
			}

			rows.add(StripRow.folder(folder));
			if (!folder.isCollapsed())
			{
				for (String tag : folder.getTags())
				{
					rows.add(StripRow.tag(tag, folder));
				}
			}
		}
		return rows;
	}

	@Nullable
	Folder folder(String id)
	{
		return folders.get(id);
	}

	@Nullable
	Folder folderOf(String tag)
	{
		for (Folder folder : folders.values())
		{
			if (folder.getTags().contains(tag))
			{
				return folder;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------
	// Mutations
	// ------------------------------------------------------------------

	/**
	 * Drag one tab onto another: the phone-homescreen gesture that makes a
	 * folder. The new folder takes the slot the target tab was occupying, so it
	 * appears exactly where the player dropped it.
	 */
	Folder createFolder(String name, String target, String dragged)
	{
		Folder folder = new Folder();
		folder.setId(newId());
		folder.setName(name);
		folder.setColor(nextColor());
		folder.getTags().add(target);
		folder.getTags().add(dragged);

		// Detach the dragged tag first, then re-read the target's index: taking
		// the index up front and detaching afterwards drops the folder a slot
		// too high whenever the dragged tab sat above its target.
		detach(dragged);
		int at = roots.indexOf(TAG + target);
		detach(target);

		folders.put(folder.getId(), folder);
		roots.add(at < 0 || at > roots.size() ? roots.size() : at, FOLDER + folder.getId());
		save();
		return folder;
	}

	/** An empty folder, added at the bottom, for filling by drag afterwards. */
	Folder createEmptyFolder(String name)
	{
		Folder folder = new Folder();
		folder.setId(newId());
		folder.setName(name);
		folder.setColor(nextColor());
		folders.put(folder.getId(), folder);
		roots.add(FOLDER + folder.getId());
		save();
		return folder;
	}

	/**
	 * Follow a tag through a rename, keeping its slot.
	 * <p>
	 * Without this, {@link #reconcile} would see the old name vanish and the new
	 * one appear, and quietly drop a renamed tab out of its folder to the bottom
	 * of the column.
	 */
	void renameTag(String from, String to)
	{
		int at = roots.indexOf(TAG + from);
		if (at >= 0)
		{
			roots.set(at, TAG + to);
		}

		for (Folder folder : folders.values())
		{
			int index = folder.getTags().indexOf(from);
			if (index >= 0)
			{
				folder.getTags().set(index, to);
			}
		}
		save();
	}

	/**
	 * The top-level slot a row occupies.
	 * <p>
	 * A tag inside a folder resolves to the folder's slot, so dropping next to
	 * a nested tab means "next to its folder" rather than nothing at all.
	 */
	private int rootSlotOf(StripRow target)
	{
		if (target.isFolder())
		{
			return roots.indexOf(FOLDER + target.getFolder().getId());
		}

		Folder parent = target.getParent();
		if (parent != null)
		{
			return roots.indexOf(FOLDER + parent.getId());
		}
		return roots.indexOf(TAG + target.getTag());
	}

	/** Drop a tag at the top level, immediately before or after a row. */
	void moveTagNear(String tag, StripRow target, boolean after)
	{
		detach(tag);

		// Resolved after detaching: removing the tag can shift every slot below
		// it, and can delete a folder it just emptied.
		int at = rootSlotOf(target);
		roots.add(at < 0 ? roots.size() : (after ? Math.min(at + 1, roots.size()) : at), TAG + tag);
		save();
	}

	/** Move a whole folder before or after a row. */
	void moveFolderNear(String folderId, StripRow target, boolean after)
	{
		String key = FOLDER + folderId;
		if (!roots.remove(key))
		{
			return;
		}

		int at = rootSlotOf(target);
		roots.add(at < 0 ? roots.size() : (after ? Math.min(at + 1, roots.size()) : at), key);
		save();
	}

	/** Drop a tag inside a folder, beside one of its existing members. */
	void moveTagIntoFolderNear(String tag, String folderId, String targetTag, boolean after)
	{
		detach(tag);

		Folder folder = folders.get(folderId);
		if (folder == null)
		{
			// detach() deletes a folder it just emptied, so the destination can
			// vanish underneath us when the tag was its only member.
			return;
		}

		List<String> tags = folder.getTags();
		int at = tags.indexOf(targetTag);
		tags.add(at < 0 ? tags.size() : (after ? Math.min(at + 1, tags.size()) : at), tag);
		save();
	}

	/** Nudge a top-level entry one slot up or down. */
	void moveRoot(String entry, int delta)
	{
		int from = roots.indexOf(entry);
		if (from < 0)
		{
			return;
		}

		int to = from + delta;
		if (to < 0 || to >= roots.size())
		{
			return;
		}

		roots.remove(from);
		roots.add(to, entry);
		save();
	}

	/** Nudge a tag one slot up or down within the folder holding it. */
	void moveWithinFolder(String tag, String folderId, int delta)
	{
		Folder folder = folders.get(folderId);
		if (folder == null)
		{
			return;
		}

		List<String> tags = folder.getTags();
		int from = tags.indexOf(tag);
		int to = from + delta;
		if (from < 0 || to < 0 || to >= tags.size())
		{
			return;
		}

		tags.remove(from);
		tags.add(to, tag);
		save();
	}

	/** Drop a tag into a folder, at {@code index} among its members, or last. */
	void moveIntoFolder(String tag, String folderId, int index)
	{
		Folder folder = folders.get(folderId);
		if (folder == null)
		{
			return;
		}

		detach(tag);

		// detach() deletes a folder it just emptied, so the destination may have
		// vanished underneath us when the tag was that folder's only member.
		if (!folders.containsKey(folderId))
		{
			return;
		}

		List<String> tags = folder.getTags();
		tags.add(index < 0 || index > tags.size() ? tags.size() : index, tag);
		save();
	}

	/** Drag a tag back out to the top level, landing at {@code index}. */
	void moveToRoot(String tag, int index)
	{
		detach(tag);
		roots.add(index < 0 || index > roots.size() ? roots.size() : index, TAG + tag);
		save();
	}

	/** Reorder a top-level entry (a loose tag, or a whole folder). */
	void reorderRoot(String entry, int index)
	{
		int from = roots.indexOf(entry);
		if (from < 0)
		{
			return;
		}
		roots.remove(from);
		roots.add(index < 0 || index > roots.size() ? roots.size() : index, entry);
		save();
	}

	/**
	 * Open whatever folder a tag is hiding in.
	 * <p>
	 * Used when the bank reopens on a remembered tag: leaving it inside a
	 * collapsed folder would show the tag's contents with nothing in the column
	 * marked active, which reads as a bug.
	 */
	void reveal(String tag)
	{
		Folder folder = folderOf(tag);
		if (folder != null && folder.isCollapsed())
		{
			folder.setCollapsed(false);
			save();
		}
	}

	/** The first palette colour no folder is using, else one by position. */
	private int nextColor()
	{
		for (int candidate : PALETTE)
		{
			boolean taken = false;
			for (Folder folder : folders.values())
			{
				if (folder.getColor() == candidate)
				{
					taken = true;
					break;
				}
			}
			if (!taken)
			{
				return candidate;
			}
		}
		return PALETTE[folders.size() % PALETTE.length];
	}

	void setColor(String folderId, int rgb)
	{
		Folder folder = folders.get(folderId);
		if (folder != null)
		{
			folder.setColor(rgb);
			save();
		}
	}

	void toggleCollapsed(String folderId)
	{
		Folder folder = folders.get(folderId);
		if (folder != null)
		{
			folder.setCollapsed(!folder.isCollapsed());
			save();
		}
	}

	void rename(String folderId, String name)
	{
		Folder folder = folders.get(folderId);
		if (folder != null)
		{
			folder.setName(name);
			save();
		}
	}

	void setIcon(String folderId, int itemId)
	{
		Folder folder = folders.get(folderId);
		if (folder != null)
		{
			folder.setIconItemId(itemId);
			save();
		}
	}

	/** Delete a folder, spilling its tags back out where it stood. */
	void dissolve(String folderId)
	{
		Folder folder = folders.remove(folderId);
		if (folder == null)
		{
			return;
		}

		int at = roots.indexOf(FOLDER + folderId);
		if (at < 0)
		{
			at = roots.size();
		}
		else
		{
			roots.remove(at);
		}

		for (String tag : folder.getTags())
		{
			roots.add(at++, TAG + tag);
		}
		save();
	}

	/** Root index of a row, for working out where a drop should land. */
	int rootIndexOf(StripRow row)
	{
		if (row.isFolder())
		{
			return roots.indexOf(FOLDER + row.getFolder().getId());
		}
		return roots.indexOf(TAG + row.getTag());
	}

	String rootKey(StripRow row)
	{
		return row.isFolder() ? FOLDER + row.getFolder().getId() : TAG + row.getTag();
	}

	/**
	 * Unlink a tag from wherever it currently sits.
	 * <p>
	 * Not saved on its own: every caller goes on to re-file the tag and saves
	 * once, so a half-applied move never reaches disk.
	 */
	private void detach(String tag)
	{
		roots.remove(TAG + tag);

		for (Folder folder : new ArrayList<>(folders.values()))
		{
			if (folder.getTags().remove(tag) && folder.getTags().isEmpty())
			{
				folders.remove(folder.getId());
				roots.remove(FOLDER + folder.getId());
			}
		}
	}

	private String newId()
	{
		String id;
		do
		{
			id = Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
		}
		while (folders.containsKey(id));
		return id;
	}
}
