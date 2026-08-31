package com.banktagfolders;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(FolderTree.GROUP)
public interface BankTagFoldersConfig extends Config
{
	@ConfigItem(
		keyName = "tagRowHeight",
		name = "Tag row height",
		description = "Height of a tag tab. The game's own tabs are 40; smaller fits more on screen.",
		position = 2
	)
	@Range(min = 24, max = 48)
	default int tagRowHeight()
	{
		return 40;
	}

	@ConfigItem(
		keyName = "folderRowHeight",
		name = "Folder row height",
		description = "Height of a folder header row.",
		position = 3
	)
	@Range(min = 16, max = 40)
	default int folderRowHeight()
	{
		return 24;
	}

	@ConfigItem(
		keyName = "protectLayoutSpacing",
		name = "Protect layout spacing",
		description = "Off by default, matching the Bank Tags plugin exactly: tagged items that are not in a tab's"
			+ " layout yet drop into the first free slots, from the top."
			+ "<br><br>Turn this on only if you deliberately leave blank slots as spacing and want them left alone --"
			+ " unlaid items are then appended below the layout instead. If your tabs rely on those items landing"
			+ " in the blanks, this will look wrong.",
		position = 4
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
