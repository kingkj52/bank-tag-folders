package com.banktagfolders;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.SoundEffectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.chatbox.ChatboxItemSearch;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.TabInterface;
import net.runelite.client.util.Text;

/**
 * Every menu option the column offers.
 * <p>
 * The tag options are ports of {@code TabInterface.opTagTab} and its helpers,
 * kept deliberately close to the originals -- including the wording of the
 * chat messages and the shape of the confirmation menus -- so that anyone
 * moving from the stock strip finds the same behaviour. The folder options are
 * ours.
 */
@Slf4j
@Singleton
public class ColumnActions
{
	/**
	 * The bank tab varbit value meaning "potion store". Core reads this from
	 * {@code PotionStorage}, whose constant is package-private, so it is
	 * restated here rather than reached for reflectively.
	 */
	private static final int BANKTAB_POTIONSTORE = 15;

	private final Client client;
	private final ClientThread clientThread;
	private final FolderTree tree;
	private final CoreBankTags core;
	private final ChatboxPanelManager chatboxPanelManager;
	private final Provider<ChatboxItemSearch> itemSearch;
	private final ChatMessageManager chatMessageManager;
	private final Provider<TagColumn> column;

	@Inject
	ColumnActions(Client client, ClientThread clientThread, FolderTree tree, CoreBankTags core,
		ChatboxPanelManager chatboxPanelManager, Provider<ChatboxItemSearch> itemSearch,
		ChatMessageManager chatMessageManager, Provider<TagColumn> column)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.tree = tree;
		this.core = core;
		this.chatboxPanelManager = chatboxPanelManager;
		this.itemSearch = itemSearch;
		this.chatMessageManager = chatMessageManager;
		this.column = column;
	}

	// ------------------------------------------------------------------
	// Tag tab options
	// ------------------------------------------------------------------

	void onTagOp(int op, StripRow row)
	{
		String tag = row.getTag();
		try
		{
			switch (op)
			{
				case TagColumn.TAB_OP_OPEN_TAG:
					openTag(tag);
					break;
				case TagColumn.TAB_OP_CHANGE_ICON:
					changeTagIcon(tag);
					break;
				case TagColumn.TAB_OP_LAYOUT:
					toggleLayout(tag);
					break;
				case TagColumn.TAB_OP_EXPORT_TAB:
					exportTab(tag);
					break;
				case TagColumn.TAB_OP_RENAME_TAB:
					renameTab(tag);
					break;
				case TagColumn.TAB_OP_DELETE_TAB:
					confirmDeleteTab(tag);
					break;
				case TagColumn.TAB_OP_UNFILE:
					unfile(row);
					break;
				default:
					break;
			}
		}
		catch (Exception e)
		{
			log.warn("Tag option {} on '{}' failed", op, tag, e);
		}
	}

	private void openTag(String tag)
	{
		if (isPotionStoreOpen())
		{
			sayPotionStoreIsOpen();
			return;
		}
		client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);

		if (tag.equals(core.activeTag()))
		{
			core.closeTag();
			core.resetBank();
		}
		else
		{
			core.openTag(tag);
		}

		client.playSoundEffect(SoundEffectID.UI_BOOP);
	}

	private void changeTagIcon(String tag)
	{
		itemSearch.get()
			.tooltipText("Change icon (" + tag + ")")
			.onItemSelected(itemId -> clientThread.invokeLater(() ->
			{
				core.setIcon(tag, itemId);
				column.get().rebuild();
			}))
			.build();
	}

	/**
	 * Turn layout mode on or off for a tag.
	 * <p>
	 * With layout mode on, the core plugin's own Duplicate-item and
	 * Remove-layout entries appear on the bank items -- those are added by
	 * {@code TabInterface.onMenuEntryAdded}, which is not gated on the strip
	 * being enabled, so the per-tab editor keeps working through this plugin.
	 */
	private void toggleLayout(String tag)
	{
		Layout layout = core.loadLayout(tag);

		if (layout == null)
		{
			layout = new Layout(tag);
			core.saveLayout(layout);
			say("Tag tab '" + tag + "' is now in layout mode. You may reorder the items without changing their order in the bank.");
		}
		else
		{
			core.removeLayout(tag);
			layout = null;
			say("Tag tab '" + tag + "' is no longer in layout mode");
		}

		if (tag.equals(core.activeTag()))
		{
			core.openTag(tag);
		}

		core.relayoutBank();
		column.get().rebuild();
	}

	private void exportTab(String tag)
	{
		List<String> data = new ArrayList<>();
		Layout layout = core.loadLayout(tag);

		data.add("banktags");
		data.add("1");
		data.add(tag);
		data.add(String.valueOf(core.iconFor(tag)));

		for (Integer item : core.itemsForTag(tag))
		{
			// Items placed by the layout are written in the layout section
			// instead, so they are not listed twice.
			if (layout == null || layout.count(item) == 0)
			{
				data.add(String.valueOf(item));
			}
		}

		if (layout != null)
		{
			data.add("layout");
			int[] l = layout.getLayout();
			for (int idx = 0; idx < l.length; ++idx)
			{
				if (l[idx] != -1)
				{
					data.add(String.valueOf(idx));
					data.add(String.valueOf(l[idx]));
				}
			}
		}

		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(Text.toCSV(data)), null);
		say("Tag tab '" + tag + "' has been copied to your clipboard!");
	}

	private void renameTab(String oldTag)
	{
		chatboxPanelManager.openTextInput("Enter new tag name for tag \"" + oldTag + "\":")
			.addCharValidator(TabInterface.FILTERED_CHARS)
			.onDone((Consumer<String>) value -> clientThread.invoke(() -> doRename(oldTag, value)))
			.build();
	}

	private void doRename(String oldTag, String value)
	{
		String newTag = Text.standardize(value);
		if (newTag.isEmpty() || newTag.equalsIgnoreCase(oldTag))
		{
			return;
		}

		if (core.hasTab(newTag))
		{
			chatboxPanelManager.openTextMenuInput("The specified bank tag already exists.")
				.option("1. Merge into existing tag \"" + newTag + "\".",
					() -> clientThread.invoke(() -> mergeTab(oldTag, newTag)))
				.option("2. Choose a different name.",
					() -> clientThread.invoke(() -> renameTab(oldTag)))
				.build();
			return;
		}

		List<String> tabs = core.tagTabs();
		int at = tabs.indexOf(oldTag);
		if (at < 0)
		{
			return;
		}

		// Read the old tab's belongings before anything is unset, or the icon
		// and layout are gone by the time they need re-filing under the new name.
		int icon = core.iconFor(oldTag);
		Layout layout = core.loadLayout(oldTag);

		tabs.set(at, newTag);
		core.setTagTabs(tabs);

		core.unsetIcon(oldTag);
		core.removeLayout(oldTag);

		if (icon > 0)
		{
			core.setIcon(newTag, icon);
		}
		if (layout != null)
		{
			core.saveLayout(new Layout(newTag, layout.getLayout()));
		}

		core.renameTagOnItems(oldTag, newTag);

		// Carry the tab's place in its folder across, so renaming does not
		// quietly drop it to the bottom of the column.
		tree.renameTag(oldTag, newTag);

		column.get().rebuild();
		core.reloadActiveTab();
	}

	private void mergeTab(String oldTag, String newTag)
	{
		boolean wasActive = oldTag.equals(core.activeTag());

		core.renameTagOnItems(oldTag, newTag);
		core.removeTabOnly(oldTag);

		if (wasActive)
		{
			core.openTag(newTag);
		}
		else
		{
			core.reloadActiveTab();
		}

		column.get().rebuild();
	}

	private void confirmDeleteTab(String tag)
	{
		chatboxPanelManager.openTextMenuInput("Delete " + tag)
			.option("1. Tab and tag from all items", () -> clientThread.invoke(() ->
			{
				core.removeTagFromItems(tag);
				deleteTab(tag);
			}))
			.option("2. Only tab", () -> clientThread.invoke(() -> deleteTab(tag)))
			.option("3. Cancel", () ->
			{
			})
			.build();
	}

	private void deleteTab(String tag)
	{
		if (tag.equals(core.activeTag()))
		{
			// Leaving the bank filtered by a tag that no longer exists shows an
			// empty bank with no obvious way back.
			core.closeTag();
			core.resetBank();
		}

		core.removeTabOnly(tag);
		column.get().rebuild();
	}

	private void unfile(StripRow row)
	{
		Folder folder = row.getParent();
		if (folder == null)
		{
			return;
		}

		// Lands directly under the folder it came out of, which is where the
		// eye is already looking.
		int at = tree.rootIndexOf(StripRow.folder(folder));
		tree.moveToRoot(row.getTag(), at < 0 ? -1 : at + 1);
		column.get().rebuild();
	}

	// ------------------------------------------------------------------
	// Folder options
	// ------------------------------------------------------------------

	void onFolderOp(int op, StripRow row)
	{
		Folder folder = row.getFolder();
		try
		{
			switch (op)
			{
				case TagColumn.FOLDER_OP_TOGGLE:
					tree.toggleCollapsed(folder.getId());
					column.get().rebuild();
					break;

				case TagColumn.FOLDER_OP_CHANGE_ICON:
					itemSearch.get()
						.tooltipText("Change icon (" + folder.getName() + ")")
						.onItemSelected(itemId -> clientThread.invokeLater(() ->
						{
							tree.setIcon(folder.getId(), itemId);
							column.get().rebuild();
						}))
						.build();
					break;

				case TagColumn.FOLDER_OP_RENAME:
					promptName("Enter new name for folder \"" + folder.getName() + "\":", name ->
					{
						tree.rename(folder.getId(), name);
						column.get().rebuild();
					});
					break;

				case TagColumn.FOLDER_OP_DELETE:
					// Only the grouping goes; the tabs inside spill back out
					// where the folder stood. Nothing here can lose a tag.
					tree.dissolve(folder.getId());
					column.get().rebuild();
					break;

				default:
					break;
			}
		}
		catch (Exception e)
		{
			log.warn("Folder option {} failed", op, e);
		}
	}

	/** Two tabs dragged together become a folder, once it has a name. */
	void createFolderFrom(String target, String dragged)
	{
		promptName("Name the new folder:", name ->
		{
			tree.createFolder(name, target, dragged);
			column.get().rebuild();
		});
	}

	// ------------------------------------------------------------------
	// New tab button
	// ------------------------------------------------------------------

	void onNewTabOp(int op)
	{
		try
		{
			switch (op)
			{
				case TagColumn.NEWTAB_OP_NEW_TAB:
					chatboxPanelManager.openTextInput("Tag name")
						.addCharValidator(TabInterface.FILTERED_CHARS)
						.onDone((Consumer<String>) value -> clientThread.invoke(() ->
						{
							String name = Text.standardize(value);
							if (!name.isEmpty() && core.createTab(name))
							{
								column.get().rebuild();
							}
						}))
						.build();
					break;

				case TagColumn.NEWTAB_OP_IMPORT_TAB:
					importTab();
					break;

				case TagColumn.NEWTAB_OP_OPEN_TAB_MENU:
					if (isPotionStoreOpen())
					{
						sayPotionStoreIsOpen();
						break;
					}
					client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);
					core.openTagTabOverview();
					break;

				case TagColumn.NEWTAB_OP_NEW_FOLDER:
					promptName("Folder name:", name ->
					{
						tree.createEmptyFolder(name);
						column.get().rebuild();
					});
					break;

				default:
					break;
			}
		}
		catch (Exception e)
		{
			log.warn("New tab option {} failed", op, e);
		}
	}

	private void importTab()
	{
		try
		{
			String dataString = Toolkit.getDefaultToolkit()
				.getSystemClipboard()
				.getData(DataFlavor.stringFlavor)
				.toString()
				.trim();

			Iterator<String> data = Text.fromCSV(dataString).iterator();
			String tag = dataString.startsWith("banktaglayoutsplugin")
				? importBankTagLayouts(data)
				: importBankTags(data);

			if (tag == null)
			{
				say("Failed to import tag tab from clipboard, invalid format.");
				return;
			}

			core.createTab(tag);
			column.get().rebuild();

			if (tag.equals(core.activeTag()))
			{
				core.resetBank();
			}

			say("Tag tab '" + tag + "' has been imported from your clipboard!");
		}
		catch (UnsupportedFlavorException | NoSuchElementException | IOException | NumberFormatException e)
		{
			log.debug("failed to import tab", e);
			say("Failed to import tag tab from clipboard, invalid format.");
		}
	}

	/** The core plugin's own export format. */
	private String importBankTags(Iterator<String> data)
	{
		String name = data.next();
		if ("banktags".equals(name))
		{
			data.next(); // version
			name = data.next();
		}

		name = filterName(name);
		if (name == null)
		{
			return null;
		}

		core.setIcon(name, Integer.parseInt(data.next()));

		while (data.hasNext())
		{
			String token = data.next();
			if ("layout".equals(token))
			{
				break;
			}
			int itemId = Integer.parseInt(token);
			// A negative id is core's marker for "tag every variant".
			core.tagItem(itemId, name, itemId < 0);
		}

		if (data.hasNext())
		{
			Layout layout = new Layout(name);
			while (data.hasNext())
			{
				int idx = Integer.parseInt(data.next());
				int itemId = Integer.parseInt(data.next());
				layout.setItemAtPos(itemId, idx);
				core.tagItem(itemId, name, false);
			}
			core.saveLayout(layout);
		}

		return name;
	}

	/** The Bank Tag Layouts plugin's format, which core also accepts. */
	private String importBankTagLayouts(Iterator<String> data)
	{
		String header = data.next();
		String name = filterName(header.substring("banktaglayoutsplugin:".length()));
		if (name == null)
		{
			return null;
		}

		Layout layout = new Layout(name);

		while (data.hasNext())
		{
			String token = data.next();
			if (token.startsWith("banktag:"))
			{
				break;
			}
			String[] parts = token.split(":");
			layout.setItemAtPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
		}

		core.setIcon(name, Integer.parseInt(data.next()));

		while (data.hasNext())
		{
			int itemId = Integer.parseInt(data.next());
			core.tagItem(itemId, name, itemId < 0);
		}

		core.saveLayout(layout);
		return name;
	}

	private String filterName(String name)
	{
		StringBuilder sb = new StringBuilder();
		for (char c : name.toCharArray())
		{
			if (TabInterface.FILTERED_CHARS.test(c))
			{
				sb.append(c);
			}
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void promptName(String prompt, Consumer<String> onDone)
	{
		chatboxPanelManager.openTextInput(prompt)
			.addCharValidator(TabInterface.FILTERED_CHARS)
			.onDone((Consumer<String>) value ->
			{
				String name = value.trim();
				if (!name.isEmpty())
				{
					// Chatbox input is driven from key events, so the callback is
					// not guaranteed to be on the client thread -- and every one
					// of these handlers goes on to rebuild widgets.
					clientThread.invoke(() -> onDone.accept(name));
				}
			})
			.build();
	}

	/**
	 * The potion store is a bank tab on the server, not just a client view, so
	 * moving off it client-side would leave deposits going to it. We cannot
	 * close it without sending an action, so we decline instead.
	 */
	boolean isPotionStoreOpen()
	{
		return client.getVarbitValue(VarbitID.BANK_CURRENTTAB) == BANKTAB_POTIONSTORE;
	}

	void sayPotionStoreIsOpen()
	{
		say("Close the potion store before opening a tag tab.");
	}

	private void say(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}
}
