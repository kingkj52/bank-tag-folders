package com.banktagfolders;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetDrag;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.util.Text;

/**
 * Folders for bank tag tabs.
 * <p>
 * An add-on to the core Bank Tags plugin, which keeps drawing its tag column
 * exactly as it always has. After each of its rebuilds this plugin hides the
 * tabs inside closed folders, adds a header row per folder, and restacks the
 * rest. Tags, icons, layouts, import and export, renaming and deleting all stay
 * with Bank Tags, and nothing is ever written into its configuration.
 * <p>
 * Handlers that touch the column run at a low priority on purpose: core builds
 * and positions its tabs on the same events, and so do the layout plugins, so
 * waiting for all of them keeps this out of their way.
 */
@Slf4j
@PluginDescriptor(
	name = "Bank Tag Folders",
	description = "Group bank tag tabs into collapsible folders",
	tags = {"bank", "tag", "tags", "tab", "tabs", "folder", "folders", "organise", "organize"}
)
public class BankTagFoldersPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private FolderTree tree;

	@Inject
	private CoreBankTags core;

	@Inject
	private TagColumn column;

	@Inject
	private ColumnActions actions;

	@Inject
	private BankTagFoldersConfig config;

	@Inject
	private ChatMessageManager chatMessageManager;

	/** So the "turn the core strip on" nudge is said once, not every bank open. */
	private boolean warned;

	private DropRepair dropRepair;

	@Provides
	BankTagFoldersConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankTagFoldersConfig.class);
	}

	@Override
	protected void startUp()
	{
		dropRepair = new DropRepair(client, column);
		eventBus.register(dropRepair);

		clientThread.invoke(() ->
		{
			tree.load();
			warned = false;
			column.attach();
			// The bank may already be open when the plugin is enabled.
			if (ready() && client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER) != null)
			{
				column.rebuild();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(dropRepair);
		clientThread.invoke(column::release);
	}

	/**
	 * Whether the core column is there to rearrange.
	 * <p>
	 * Deliberately not something we fix by writing to the core plugin's config:
	 * silently flipping another plugin's setting is the kind of surprise that is
	 * hard to attribute later.
	 */
	private boolean ready()
	{
		return core.isAvailable() && core.isCoreStripEnabled();
	}

	// ------------------------------------------------------------------
	// Bank lifecycle
	// ------------------------------------------------------------------

	@Subscribe(priority = -10)
	public void onScriptPreFired(ScriptPreFired event)
	{
		int id = event.getScriptId();

		if (id == ScriptID.BANKMAIN_INIT)
		{
			if (ready())
			{
				// Bank Tags reopens the last tag as the bank initialises; the
				// folder holding it is opened once the column has been drawn.
				column.requestReveal();
			}
			else
			{
				nudge();
			}
		}
		else if (id == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			if (ready())
			{
				column.rebuild();
			}
		}
		else if (id == ScriptID.BANKMAIN_SIZE_CHECK && ready())
		{
			onSizeCheck();
		}
	}

	private void nudge()
	{
		if (warned || !core.isAvailable())
		{
			return;
		}

		warned = true;
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage("Bank Tag Folders is idle: turn on \"Tag tabs\" in the Bank Tags plugin "
				+ "and your folders will appear on its column.")
			.build());
	}

	/**
	 * Detect a bank resize the way core does: the script is about to resize the
	 * container, so the new size is compared against the current one and the
	 * layout is redone once it has been applied. Ours is queued after core's.
	 */
	private void onSizeCheck()
	{
		int[] stack = client.getIntStack();
		int size = client.getIntStackSize();
		if (size < 5)
		{
			return;
		}

		Widget widget = client.getWidget(stack[size - 5]);
		if (widget == null)
		{
			return;
		}

		if (widget.getWidth() != stack[size - 4] || widget.getHeight() != stack[size - 3])
		{
			clientThread.invokeLater(column::layoutRows);
		}
	}

	// The storage popup covers part of the column, so core repacks its rows into
	// the height that is left and we repack ours into the same space.
	@Subscribe(priority = -10)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_POPUP_TAB_DRAW)
		{
			column.layoutRows();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload())
		{
			column.onBankClosed();
		}
	}

	/**
	 * Core rebuilds its tabs from inside its own option handlers (delete, rename,
	 * import) and none of them raises an event. Noticing the new widgets here is
	 * what puts the folders back.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		column.heal();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (BankTagsPlugin.CONFIG_GROUP.equals(event.getGroup()))
		{
			if (BankTagsPlugin.TAG_TABS_CONFIG.equals(event.getKey()))
			{
				followRename(csv(event.getOldValue()), csv(event.getNewValue()));
			}
			else if ("useTabs".equals(event.getKey()))
			{
				warned = false;
			}
			return;
		}

		// Only settings, never our own stored structure. reconcile() saves the
		// folder tree during a draw, and reacting to that would schedule another
		// draw, which reconciles, which saves.
		if (FolderTree.GROUP.equals(event.getGroup())
			&& !FolderTree.KEY_FOLDERS.equals(event.getKey())
			&& !FolderTree.KEY_ROOTS.equals(event.getKey()))
		{
			clientThread.invokeLater(column::rebuild);
		}
	}

	/**
	 * Carry a renamed tab's place in its folder across. Without this the old name
	 * would read as deleted and the new one as new, and a renamed tab would drop
	 * out of its folder to the bottom of the column. Done here, as core writes
	 * the list, so it is settled before the column is drawn again.
	 */
	private void followRename(List<String> before, List<String> after)
	{
		String[] renamed = FolderTree.renamed(before, after);
		if (renamed != null)
		{
			tree.renameTag(renamed[0], renamed[1]);
		}
	}

	private static List<String> csv(@Nullable String value)
	{
		if (value == null || value.isEmpty())
		{
			return new ArrayList<>();
		}
		return new ArrayList<>(Text.fromCSV(value));
	}

	// ------------------------------------------------------------------
	// Menu
	// ------------------------------------------------------------------

	/**
	 * Clicks on the two options this plugin adds to core's widgets, and, when
	 * asked for, the tab click itself. Consumed so core's listener, which has no
	 * idea what they mean, is never run for them.
	 */
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		Widget widget = event.getWidget();
		if (widget == null || !column.isDrawn())
		{
			return;
		}

		String option = event.getMenuOption();
		if (TagColumn.OPTION_NEW_FOLDER.equals(option) && column.isNewTab(widget))
		{
			event.consume();
			actions.newFolder();
			return;
		}

		StripRow row = column.rowFor(widget);
		if (row == null || !row.isTag())
		{
			return;
		}

		if (TagColumn.OPTION_UNFILE.equals(option))
		{
			event.consume();
			actions.unfile(row);
		}
		else if (TagColumn.OPTION_VIEW_TAG.equals(option) && config.protectLayoutSpacing())
		{
			event.consume();
			actions.openTag(row.getTag());
		}
	}

	// ------------------------------------------------------------------
	// Dragging
	// ------------------------------------------------------------------

	/**
	 * Runs before core, because core's handling of a drop onto the column would
	 * do the wrong thing with our rows. Nulling the drop target is how core
	 * cancels a drop itself for its "prevent tag tab drags" option.
	 * <p>
	 * <ul>
	 * <li>A tab or folder being carried: core would swap tabs by name, which
	 * scrambles a folder's order, so the drop is taken off it and handled here.</li>
	 * <li>Anything dropped on a folder row: core would read a bank item as "tag
	 * this item with the folder's name" and create a tag nobody asked for.</li>
	 * <li>Anything held over a scroll arrow: core's scrolling counts flat tabs,
	 * ours counts rows.</li>
	 * </ul>
	 * Every other drop is left alone, so tagging an item by dropping it on a tab
	 * works exactly as it does without this plugin.
	 */
	@Subscribe(priority = 100)
	public void onWidgetDrag(WidgetDrag event)
	{
		if (!column.isDrawn())
		{
			return;
		}

		Widget dragged = client.getDraggedWidget();
		if (dragged == null)
		{
			return;
		}

		Widget target = client.getDraggedOnWidget();
		boolean released = client.getMouseCurrentButton() == 0;
		int arrow = column.arrowDirection(target);
		StripRow source = column.rowFor(dragged);

		if (source == null && (target == null || !(column.isOurs(target) || arrow != 0)))
		{
			return;
		}

		client.setDraggedOnWidget(null);

		if (!released)
		{
			if (arrow != 0)
			{
				column.scrollTick(arrow);
			}
			return;
		}

		if (source == null)
		{
			return;
		}

		try
		{
			handleDrop(source);
		}
		catch (Exception e)
		{
			log.warn("Tag column drop failed", e);
		}
	}

	/**
	 * Draw the column again once core has finished with a drop.
	 * <p>
	 * Core rebuilds its tabs on release for every drop it does not otherwise
	 * handle, moving a bank item included, and that rebuild takes our changes
	 * with it. Leaving it to the next tick shows a frame of the flat column.
	 * <p>
	 * A separate object because the event bus requires a subscriber to be named
	 * after its event, so one class cannot subscribe to {@code WidgetDrag} twice,
	 * and the handler above has to stay ahead of core.
	 */
	static class DropRepair
	{
		private final Client client;
		private final TagColumn column;

		DropRepair(Client client, TagColumn column)
		{
			this.client = client;
			this.column = column;
		}

		@Subscribe(priority = -100)
		public void onWidgetDrag(WidgetDrag event)
		{
			if (client.getMouseCurrentButton() == 0)
			{
				column.heal();
			}
		}
	}

	private void handleDrop(StripRow source)
	{
		TagColumn.Drop drop = column.dropAt(client.getMouseCanvasPosition());
		if (drop == null)
		{
			// Released inside the column but past the last row: send it to the
			// bottom of the top level. Released outside: just tidy up.
			if (column.containsCanvasPoint(client.getMouseCanvasPosition()))
			{
				if (source.isTag())
				{
					tree.moveToRoot(source.getTag(), -1);
				}
				else
				{
					tree.reorderRoot(tree.rootKey(source), -1);
				}
			}
			column.rebuild();
			return;
		}

		if (source.isTag())
		{
			dropTag(source, drop);
		}
		else
		{
			dropFolder(source, drop);
		}
	}

	private void dropTag(StripRow source, TagColumn.Drop drop)
	{
		String tag = source.getTag();
		StripRow target = drop.getRow();
		TagColumn.DropZone zone = drop.getZone();

		if (target.sameAs(source))
		{
			column.rebuild();
			return;
		}

		if (target.isFolder())
		{
			Folder folder = target.getFolder();
			if (zone == TagColumn.DropZone.INTO)
			{
				tree.moveIntoFolder(tag, folder.getId(), -1);
				// Expanding shows the tag landing where it was put, instead of
				// it seeming to vanish into a closed folder.
				if (folder.isCollapsed())
				{
					tree.toggleCollapsed(folder.getId());
				}
			}
			else if (zone == TagColumn.DropZone.BEFORE)
			{
				tree.moveTagNear(tag, target, false);
			}
			else if (folder.isCollapsed())
			{
				tree.moveTagNear(tag, target, true);
			}
			else
			{
				// Just below an open folder's header reads as "first one inside".
				tree.moveIntoFolder(tag, folder.getId(), 0);
			}

			column.rebuild();
			return;
		}

		Folder targetFolder = target.getParent();

		if (zone == TagColumn.DropZone.INTO)
		{
			if (targetFolder != null)
			{
				tree.moveTagIntoFolderNear(tag, targetFolder.getId(), target.getTag(), true);
				column.rebuild();
			}
			else
			{
				// Two loose tabs dropped together: the phone-homescreen gesture.
				// The tabs only move once the folder has been named, so backing
				// out of the prompt leaves the column as it was.
				actions.createFolderFrom(target.getTag(), tag);
				column.rebuild();
			}
			return;
		}

		boolean after = zone == TagColumn.DropZone.AFTER;
		if (targetFolder != null)
		{
			tree.moveTagIntoFolderNear(tag, targetFolder.getId(), target.getTag(), after);
		}
		else
		{
			tree.moveTagNear(tag, target, after);
		}
		column.rebuild();
	}

	private void dropFolder(StripRow source, TagColumn.Drop drop)
	{
		StripRow target = drop.getRow();
		Folder folder = source.getFolder();

		// Dropping a folder onto its own contents would ask it to reorder
		// relative to itself.
		if (target.sameAs(source)
			|| (target.getParent() != null && target.getParent().getId().equals(folder.getId())))
		{
			column.rebuild();
			return;
		}

		// Folders reorder only. Nesting one inside another has no way to be
		// drawn, so a drop into the middle of a row is treated as "after it".
		tree.moveFolderNear(folder.getId(), target, drop.getZone() != TagColumn.DropZone.BEFORE);
		column.rebuild();
	}
}
