package com.banktagfolders;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A named, collapsible group of bank tags.
 * <p>
 * Folders live entirely in this plugin's own config group. Nothing about them
 * is written back into {@code banktags}, so turning this plugin off leaves the
 * player with exactly the flat tag list they had before -- no orphaned
 * pseudo-tabs, no reordering, nothing to clean up.
 */
@Data
public class Folder
{
	/**
	 * Stable identity, generated once at creation.
	 * <p>
	 * Deliberately not the folder's name: renaming has to be a pure relabel, and
	 * keying on the name would orphan every row pointing at the old one.
	 */
	private String id;

	private String name;

	/**
	 * Item sprite drawn on the folder row. Zero means "borrow the icon of the
	 * first tag inside", which keeps a freshly dragged-together folder from
	 * showing up blank before the player has picked anything.
	 */
	private int iconItemId;

	private boolean collapsed;

	/** Member tags, in display order. */
	private List<String> tags = new ArrayList<>();
}
