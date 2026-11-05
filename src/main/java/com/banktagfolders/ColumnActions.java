package com.banktagfolders;

import java.util.function.Consumer;
import javax.annotation.Nullable;
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
import net.runelite.client.plugins.banktags.tabs.TabInterface;

/**
 * The options this plugin adds: everything on a folder, plus the two additions
 * to a tab and to the new-tab button. Every other tab option is core's.
 */
@Slf4j
@Singleton
public class ColumnActions
{
	/**
	 * The bank tab varbit value meaning "potion store". Core reads this from
	 * {@code PotionStorage}, whose constant is package-private, so it is
	 * restated here.
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
	// Tab options
	// ------------------------------------------------------------------

	/**
	 * Open or close a tag, for when the layout spacing option is on and core's
	 * own click has been held back. Mirrors what core's "View tag tab" does.
	 */
	void openTag(String tag)
	{
		if (isPotionStoreOpen())
		{
			// The potion store is a bank tab on the server, not just a client view,
			// so moving off it client-side would leave deposits going to it. We
			// cannot close it without sending an action, so we decline instead.
			say("Close the potion store before opening a tag tab.");
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

	void unfile(StripRow row)
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

				case TagColumn.FOLDER_OP_SET_COLOUR:
					setFolderColour(folder);
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

	/**
	 * Ask for a colour as hex. There is no colour picker reachable from the
	 * bank, and the chatbox is already how this plugin asks for names.
	 */
	private void setFolderColour(Folder folder)
	{
		chatboxPanelManager.openTextInput("Folder colour as hex (e.g. 3a6b8b):")
			.addCharValidator(TabInterface.FILTERED_CHARS)
			.onDone((Consumer<String>) value -> clientThread.invoke(() ->
			{
				Integer rgb = parseHexColour(value);
				if (rgb == null)
				{
					say("That is not a hex colour. Try six hex digits, like 3a6b8b.");
					return;
				}
				tree.setColor(folder.getId(), rgb);
				column.get().rebuild();
			}))
			.build();
	}

	/** Accepts rgb or rrggbb, with or without a leading hash. */
	@Nullable
	static Integer parseHexColour(@Nullable String value)
	{
		String hex = value == null ? "" : value.trim().replace("#", "");
		if (hex.length() == 3)
		{
			StringBuilder expanded = new StringBuilder();
			for (char c : hex.toCharArray())
			{
				expanded.append(c).append(c);
			}
			hex = expanded.toString();
		}

		if (hex.length() != 6)
		{
			return null;
		}

		try
		{
			return Integer.parseInt(hex, 16);
		}
		catch (NumberFormatException e)
		{
			return null;
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

	/** An empty folder from the new-tab button, to be filled by dragging. */
	void newFolder()
	{
		promptName("Folder name:", name ->
		{
			tree.createEmptyFolder(name);
			column.get().rebuild();
		});
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
					// not guaranteed to be on the client thread, and every one of
					// these handlers goes on to rebuild widgets.
					clientThread.invoke(() -> onDone.accept(name));
				}
			})
			.build();
	}

	private boolean isPotionStoreOpen()
	{
		return client.getVarbitValue(VarbitID.BANK_CURRENTTAB) == BANKTAB_POTIONSTORE;
	}

	void say(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(message)
			.build());
	}
}
