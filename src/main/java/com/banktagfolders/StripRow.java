package com.banktagfolders;

import javax.annotation.Nullable;

/**
 * One row of the tag column: either a folder header or a tag tab.
 * <p>
 * Rows are derived fresh from {@link FolderTree} on every render and thrown
 * away afterwards. They exist so the renderer and the drag handler agree on
 * what a given widget represents without either of them re-walking the tree.
 */
class StripRow
{
	enum Kind
	{
		FOLDER,
		TAG
	}

	private final Kind kind;

	@Nullable
	private final String tag;

	@Nullable
	private final Folder folder;

	/** The folder this tag sits inside, or null when the tag is top level. */
	@Nullable
	private final Folder parent;

	private StripRow(Kind kind, @Nullable String tag, @Nullable Folder folder, @Nullable Folder parent)
	{
		this.kind = kind;
		this.tag = tag;
		this.folder = folder;
		this.parent = parent;
	}

	static StripRow folder(Folder folder)
	{
		return new StripRow(Kind.FOLDER, null, folder, null);
	}

	static StripRow tag(String tag, @Nullable Folder parent)
	{
		return new StripRow(Kind.TAG, tag, null, parent);
	}

	boolean isFolder()
	{
		return kind == Kind.FOLDER;
	}

	boolean isTag()
	{
		return kind == Kind.TAG;
	}

	/** True when this tag is nested, and so should be drawn indented. */
	boolean isNested()
	{
		return parent != null;
	}

	@Nullable
	String getTag()
	{
		return tag;
	}

	@Nullable
	Folder getFolder()
	{
		return folder;
	}

	@Nullable
	Folder getParent()
	{
		return parent;
	}

	/**
	 * Whether two rows stand for the same thing.
	 * <p>
	 * Compared by what they name rather than by identity: rows are rebuilt from
	 * scratch on every render, so two objects describing the same tab are
	 * routine.
	 */
	boolean sameAs(@Nullable StripRow other)
	{
		if (other == null || other.kind != kind)
		{
			return false;
		}
		if (kind == Kind.FOLDER)
		{
			return folder.getId().equals(other.folder.getId());
		}
		return tag.equals(other.tag);
	}
}
