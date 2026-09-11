package com.banktagfolders;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(FolderTree.GROUP)
public interface BankTagFoldersConfig extends Config
{
	/**
	 * The tab background sprite is 40 pixels tall and is drawn tiled, so a taller
	 * row repeats it and shows the top edge of a second tab inside the first.
	 */
	int MAX_ROW_HEIGHT = 40;


	@ConfigItem(
		keyName = "tagRowHeight",
		name = "Tag row height",
		description = "Height of a row, folders included. 24 to 40, since the game's tab background is itself 40"
			+ " tall and repeats above that. Smaller fits more rows on screen.",
		position = 2
	)
	@Range(min = 24, max = MAX_ROW_HEIGHT)
	default int tagRowHeight()
	{
		return 40;
	}

	@ConfigItem(
		keyName = "tagTintStrength",
		name = "Tag tint strength",
		description = "How strongly a folder's colour tints the tag tabs inside it."
			+ " Zero leaves them looking like ordinary tabs.",
		position = 4
	)
	@Range(max = 100)
	default int tagTintStrength()
	{
		return 35;
	}

	@ConfigItem(
		keyName = "folderTintStrength",
		name = "Folder tint strength",
		description = "How strongly a folder's colour fills the folder's own row."
			+ " Zero leaves only the icon and the expand marker.",
		position = 5
	)
	@Range(max = 100)
	default int folderTintStrength()
	{
		return 68;
	}

	@ConfigItem(
		keyName = "protectLayoutSpacing",
		name = "Protect layout spacing",
		description = "Off by default, matching the Bank Tags plugin exactly: tagged items that are not in a tab's"
			+ " layout yet drop into the first free slots, from the top."
			+ "<br><br>Turn this on only if you deliberately leave blank slots as spacing and want them left alone --"
			+ " unlaid items are then appended below the layout instead. If your tabs rely on those items landing"
			+ " in the blanks, this will look wrong.",
		position = 6
	)
	default boolean protectLayoutSpacing()
	{
		// Deliberately matching core rather than "protecting" by default. A tab
		// whose blank slots are where its untracked items are *meant* to land
		// renders wrong the moment they are appended instead -- which is not a
		// trade to make on a player's behalf without them asking.
		return false;
	}
}
