package com.banktagfolders;

import com.google.inject.Provides;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetDrag;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;

/**
 * Folders for bank tag tabs.
 * <p>
 * Requires the core Bank Tags plugin to be <em>enabled</em> -- it owns the tags,
 * the layouts and the sprites, and most of its bank-item behaviour keeps
 * working through it -- but with its own tab strip <em>switched off</em>, since
 * both strips build their widgets in the same place. The plugin stays dormant
 * and says so rather than fighting for the space.
 * <p>
 * The event wiring mirrors {@code TabInterface} deliberately, down to doing the
 * work in {@code ScriptPreFired} for {@code BANKMAIN_FINISHBUILDING}: the
 * bank's scroll height is still an argument on the interpreter stack at that
 * point, and the tag-tab overview needs to overwrite it.
 */
@Slf4j
@PluginDescriptor(
	name = "Bank Tag Folders",
	description = "Group bank tag tabs into collapsible folders",
	tags = {"bank", "tag", "tags", "tab", "tabs", "folder", "folders", "organise", "organize"}
)
public class BankTagFoldersPlugin extends Plugin
{
	/** Argument offset of the scroll height in bankmain_finishbuilding. */
	private static final int SCROLL_HEIGHT_ARG = 9;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private FolderTree tree;

	@Inject
	private CoreBankTags core;

	@Inject
	private TagColumn column;

	@Inject
	private ColumnActions actions;

	@Inject
	private ChatMessageManager chatMessageManager;

	/** So the "turn the core strip off" nudge is said once, not every bank open. */
	private boolean warned;

	@Provides
	BankTagFoldersConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankTagFoldersConfig.class);
	}

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			tree.load();
			warned = false;
			// The bank may already be open when the plugin is enabled.
			if (canRender() && client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER) != null)
			{
				column.rebuild();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(column::deinit);
	}

	/**
	 * Whether we should be drawing at all.
	 * <p>
	 * Deliberately not something we fix by writing to the core plugin's config:
	 * silently flipping another plugin's setting is the kind of surprise that is
	 * hard to attribute later.
	 */
	private boolean canRender()
	{
		if (!core.isAvailable())
		{
			return false;
		}

		if (core.isCoreStripEnabled())
		{
			if (!warned)
			{
				warned = true;
				chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.CONSOLE)
					.runeLiteFormattedMessage("Bank Tag Folders is idle: turn off \"Tag tabs\" in the "
						+ "Bank Tags plugin so this plugin can draw the tag column instead.")
					.build());
			}
			return false;
		}

		warned = false;
		return true;
	}

	/** True while the core plugin's "every tab as an icon" view is open. */
	private boolean isOverviewActive()
	{
		return CoreBankTags.TAGTABS.equals(core.activeTag());
	}

	// ------------------------------------------------------------------
	// Bank lifecycle
	// ------------------------------------------------------------------

	/**
	 * Runs last of everything on this event.
	 * <p>
	 * The core layout manager and the Bank Tag Layouts plugin both lay the bank
	 * out from {@code BANKMAIN_FINISHBUILDING} too, and Bank Tag Layouts decides
	 * whether to do anything at all by reading {@code TabInterface.getActiveTag()}
	 * -- bailing out entirely when it is null. Drawing our column after both of
	 * them have had the bank keeps us out of that decision.
	 */
	@Subscribe(priority = -10)
	public void onScriptPreFired(ScriptPreFired event)
	{
		int id = event.getScriptId();

		if (id == ScriptID.BANKMAIN_INIT)
		{
			if (canRender())
			{
				boolean wasOpen = column.isInitialised();
				column.init();
				if (!wasOpen && column.isInitialised())
				{
					// Deferred rather than run inline. Reopening a tag forces a
					// bank relayout, and driving that from inside the bank's own
					// init means the layout code can run against a bank that has
					// not been filtered to the tag yet -- which makes every item
					// in the bank look like one the layout is missing.
					clientThread.invokeLater(this::restoreLastTag);
				}
			}
			return;
		}

		// Not just "are we set up" -- if the core strip has since been switched
		// back on we must stand down immediately, or both strips build into the
		// same container and fight over it every rebuild.
		if (!column.isInitialised())
		{
			return;
		}

		if (core.isCoreStripEnabled())
		{
			column.deinit();
			return;
		}

		// Note: BANKMAIN_SEARCH_TOGGLE is deliberately not handled. Core's
		// TabInterface already drops the active tag when the bank search opens,
		// and it does so whether or not its own strip is drawing -- so doing it
		// here as well only nulls TabInterface's active tag a second time, and
		// that field is what Bank Tag Layouts reads to decide whether to lay the
		// bank out at all.
		if (id == ScriptID.BANKMAIN_SIZE_CHECK)
		{
			onSizeCheck();
		}
		else if (id == ScriptID.BANKMAIN_FINISHBUILDING)
		{
			onFinishBuilding();
		}
	}

	/**
	 * Reopen whatever the bank was last showing.
	 * <p>
	 * The core strip does this in its own {@code init()}, which never runs while
	 * it is switched off -- so without this the bank falls back to the vanilla
	 * numbered tab the server resyncs, and a tag that was open when the bank
	 * closed is forgotten. Nothing needs saving here; the core plugin has been
	 * recording the tag all along.
	 */
	private void restoreLastTag()
	{
		if (!core.remembersLastTab())
		{
			return;
		}

		String tab = core.lastTab();
		if (tab == null)
		{
			return;
		}

		if (actions.isPotionStoreOpen())
		{
			return;
		}

		// The server resyncs the last vanilla tab as the bank opens, and it
		// would otherwise win.
		client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);

		if (CoreBankTags.TAGTABS.equals(tab))
		{
			core.openTagTabOverview();
		}
		else if (core.hasTab(tab))
		{
			core.openTag(tab);
			// Open the folder it lives in and scroll to it, so the reopened tag
			// is visibly the active one rather than hidden inside a closed
			// folder. Deferred until the column has rows to scroll among.
			column.requestReveal(tab);
		}
		// A tag deleted since the bank was last open just falls through: opening
		// it would show an empty bank with no obvious way back.
	}

	/**
	 * Detect a bank resize, exactly as the core strip does: the script is about
	 * to resize the container, so the new size is compared against the current
	 * one and the relayout is deferred until after it has been applied.
	 */
	private void onSizeCheck()
	{
		int[] stack = client.getIntStack();
		int size = client.getIntStackSize();
		Widget widget = client.getWidget(stack[size - 5]);
		if (widget == null)
		{
			return;
		}

		int width = stack[size - 4];
		int height = stack[size - 3];
		if (widget.getWidth() != width || widget.getHeight() != height)
		{
			clientThread.invokeLater(column::relayout);
		}
	}

	private void onFinishBuilding()
	{
		boolean overview = isOverviewActive();
		if (overview)
		{
			column.hideBank();
		}

		column.rebuild();
		int overviewHeight = column.rebuildOverview(overview);

		Widget title = client.getWidget(InterfaceID.Bankmain.TITLE);
		if (title != null)
		{
			if (overview)
			{
				title.setText("Tag tab tab");
			}
			else if (core.activeTag() != null)
			{
				title.setText("Tag tab <col=ff0000>" + core.activeTag() + "</col>");
			}
		}

		if (overview)
		{
			// The overview draws icons rather than real items, so the bank's own
			// computed scroll height is wrong. This runs before the script does,
			// so its arguments are still on the stack and can be overwritten.
			int[] stack = client.getIntStack();
			int size = client.getIntStackSize();
			stack[size - SCROLL_HEIGHT_ARG] = overviewHeight;
		}
	}

	/**
	 * Runs last on this event too, matching the pre-fire handler.
	 * <p>
	 * The core layout manager and Bank Tag Layouts both lay the bank out around
	 * these scripts; touching the column only after they are done is what keeps
	 * us out of their way.
	 */
	@Subscribe(priority = -10)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.BANKMAIN_POPUP_TAB_DRAW && column.isInitialised())
		{
			// The storage popup covers part of the column, so the rows have to
			// be re-packed into whatever height is left.
			column.relayout();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN && event.isUnload())
		{
			column.deinit();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Only settings, never our own stored structure. reconcile() saves the
		// folder tree during a render, and reacting to that would schedule
		// another render, which reconciles, which saves -- the bank rebuilding
		// itself in a circle for no reason.
		boolean ours = FolderTree.GROUP.equals(event.getGroup())
			&& !FolderTree.KEY_FOLDERS.equals(event.getKey())
			&& !FolderTree.KEY_ROOTS.equals(event.getKey());
		boolean coreTabs = BankTagsPlugin.CONFIG_GROUP.equals(event.getGroup()) && "tabs".equals(event.getKey());
		if (!ours && !coreTabs)
		{
			return;
		}

		if (coreTabs)
		{
			warned = false;
		}

		clientThread.invokeLater(() ->
		{
			if (!canRender())
			{
				column.deinit();
				return;
			}
			if (client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER) != null)
			{
				column.rebuild();
			}
		});
	}

	// ------------------------------------------------------------------
	// Dragging
	// ------------------------------------------------------------------

	/**
	 * Drops onto the column.
	 * <p>
	 * {@link WidgetDrag} carries nothing itself; the client holds what was
	 * picked up and what it was released over, which is how the core strip reads
	 * its own tab reordering.
	 */
	@Subscribe
	public void onWidgetDrag(WidgetDrag event)
	{
		if (!column.isInitialised() || !canRender())
		{
			return;
		}

		Widget dragged = client.getDraggedWidget();
		if (dragged == null)
		{
			return;
		}

		try
		{
			if (client.getMouseCurrentButton() == 0)
			{
				handleDrop(dragged, client.getDraggedOnWidget(), client.isKeyPressed(KeyCode.KC_SHIFT));
			}
			else
			{
				handleDragInProgress(dragged);
			}
		}
		catch (Exception e)
		{
			log.warn("Tag column drag failed", e);
		}
	}

	/**
	 * While the mouse is still down, keep the arrows scrolling when something is
	 * held over them.
	 * <p>
	 * Core also rewrites the pending menu entry here so a drag reads as
	 * "tag:<tab>". That is deliberately not done: this plugin already tags the
	 * item itself when the drag is released, so the rewrite would be a second
	 * route to the same result, and mutating a menu entry is exactly the kind of
	 * thing the third-party client guidelines are written about. Not doing it
	 * costs a little hover text and nothing else.
	 */
	private void handleDragInProgress(Widget dragged)
	{
		if (dragged.getItemId() == -1)
		{
			return;
		}

		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries.length == 0)
		{
			return;
		}

		String option = entries[entries.length - 1].getOption();
		if (TagColumn.SCROLL_UP.equals(option))
		{
			column.scrollTick(-1);
		}
		else if (TagColumn.SCROLL_DOWN.equals(option))
		{
			column.scrollTick(1);
		}
	}

	private void handleDrop(Widget dragged, @Nullable Widget draggedOn, boolean shift)
	{
		StripRow source = column.rowFor(dragged);

		if (source == null)
		{
			tagByDrop(dragged, column.rowFor(draggedOn), shift);
			return;
		}

		TagColumn.Drop drop = column.dropAt(client.getMouseCanvasPosition());
		if (drop == null)
		{
			// Released inside the column but past the last row: send it to the
			// bottom of the top level. Released outside: just tidy up the widget
			// that was left showing at the cursor.
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

	/** A bank item dragged onto a tab tags it, as the core strip allows. */
	private void tagByDrop(Widget dragged, @Nullable StripRow target, boolean shift)
	{
		if (target == null || !target.isTag())
		{
			return;
		}

		// Dynamic children report their parent's packed id, so this is "an item
		// from the bank's item list" rather than any item widget anywhere.
		if (dragged.getId() != InterfaceID.Bankmain.ITEMS || dragged.getItemId() == -1)
		{
			return;
		}

		core.tagItem(dragged.getItemId(), target.getTag(), shift);
		core.reloadActiveTab();
		column.rebuild();
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
