package com.banktagfolders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FontID;
import net.runelite.api.Point;
import net.runelite.api.ScriptEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfig;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.tabs.TabSprites;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;

/**
 * The tag column: a faithful rebuild of the core plugin's tab strip, with
 * folders layered on top.
 * <p>
 * The widget model is copied from {@code TabInterface} because it has to be.
 * The strip lives in the <em>dynamic</em> children of
 * {@code Bankmain.ITEMS_CONTAINER}: a handful of persistent widgets first (the
 * scroll catcher, the two arrows and the new-tab button), then the rows after
 * them. Rows are created once per bank rebuild and only <em>repositioned</em>
 * when scrolling, which is why scrolling stays cheap.
 * <p>
 * Rows are packed against a pixel budget rather than counted against a fixed tab
 * height as core does, so a row is free to be a different size without the
 * scrolling arithmetic caring.
 */
@Slf4j
@Singleton
public class TagColumn
{
	private static final java.awt.Color HILIGHT_COLOR = JagexColors.MENU_TARGET;

	static final String SCROLL_UP = "Scroll up";
	static final String SCROLL_DOWN = "Scroll down";

	private static final int TAB_WIDTH = 39;
	private static final int MARGIN = 1;
	private static final int BUTTON_HEIGHT = 20;
	private static final int NEW_TAB_HEIGHT = 39;
	/** Offset from the bottom of the bank container, as core measures it. */
	private static final int BANK_BOTTOM_OFFSET = 39;
	/** Rate limit for hold-to-scroll on the arrow buttons, in milliseconds. */
	private static final int SCROLL_TICK = 500;

	/** Ops, numbered as core numbers them: registered at index, read as index+1. */
	static final int TAB_OP_OPEN_TAG = 1;
	static final int TAB_OP_CHANGE_ICON = 2;
	static final int TAB_OP_LAYOUT = 3;
	static final int TAB_OP_EXPORT_TAB = 4;
	static final int TAB_OP_RENAME_TAB = 5;
	static final int TAB_OP_DELETE_TAB = 6;
	/** Ours, and only present on a tag that is actually inside a folder. */
	static final int TAB_OP_UNFILE = 7;

	static final int FOLDER_OP_TOGGLE = 1;
	static final int FOLDER_OP_CHANGE_ICON = 2;
	static final int FOLDER_OP_SET_COLOUR = 3;
	static final int FOLDER_OP_RENAME = 4;
	static final int FOLDER_OP_DELETE = 5;

	static final int NEWTAB_OP_NEW_TAB = 1;
	static final int NEWTAB_OP_IMPORT_TAB = 2;
	static final int NEWTAB_OP_OPEN_TAB_MENU = 3;
	static final int NEWTAB_OP_NEW_FOLDER = 4;

	private static final int FOLDER_TEXT = 0xFFFFFF;

	private final Client client;
	private final BankTagFoldersConfig config;
	private final FolderTree tree;
	private final CoreBankTags core;

	/**
	 * Lazily resolved to break the injection cycle: the actions need to ask for
	 * a redraw when they finish, and the column needs to hand them clicks.
	 */
	private final Provider<ColumnActions> actions;

	private Widget parent;
	private Widget scrollComponent;
	private Widget upButton;
	private Widget downButton;
	private Widget newTab;

	@Getter
	private boolean initialised;

	/**
	 * How many dynamic children the bank container already had before we added
	 * ours. Core assumes zero; measuring instead means a stray child from
	 * elsewhere is left alone rather than deleted.
	 */
	private int baseChildCount;

	private int scrollRow;
	private int availableHeight;
	private Instant startScroll = Instant.now();

	private List<StripRow> rows = new ArrayList<>();
	private final List<RenderedRow> rendered = new ArrayList<>();
	private final Map<Widget, StripRow> rowByWidget = new IdentityHashMap<>();

	/**
	 * The icons we appended to the bank's item list for the tag-tab overview.
	 * <p>
	 * Held by reference rather than by index. The bank's own item widgets live
	 * in the same container, and an index remembered from one bank state is a
	 * licence to hide somebody else's widgets in the next.
	 */
	private final List<Widget> overviewIcons = new ArrayList<>();

	/**
	 * A tag to open the folder of and scroll to on the next rebuild.
	 * <p>
	 * Deferred because the request arrives as the bank initialises, well before
	 * there are any rows to scroll among.
	 */
	@Nullable
	private String pendingReveal;

	@Inject
	TagColumn(Client client, BankTagFoldersConfig config, FolderTree tree, CoreBankTags core,
		Provider<ColumnActions> actions)
	{
		this.client = client;
		this.config = config;
		this.tree = tree;
		this.core = core;
		this.actions = actions;
	}

	/** One widget belonging to a row, and its offset below the row's top. */
	private static class Piece
	{
		private final Widget widget;
		private final int dy;

		Piece(Widget widget, int dy)
		{
			this.widget = widget;
			this.dy = dy;
		}
	}

	/** A row's widgets, created once per rebuild and repositioned on scroll. */
	private static class RenderedRow
	{
		private final StripRow row;
		private final int height;
		private final List<Piece> pieces = new ArrayList<>();

		/** Where the row last landed, or -1 while it is scrolled out of view. */
		private int top = -1;

		RenderedRow(StripRow row, int height)
		{
			this.row = row;
			this.height = height;
		}
	}

	/** Where a drop landed relative to the row under the cursor. */
	enum DropZone
	{
		BEFORE,
		INTO,
		AFTER
	}

	/**
	 * A resolved drop: the row under the cursor and which part of it was hit.
	 * <p>
	 * The distinction is what lets one gesture do two jobs. Dropping into the
	 * middle of a tab groups the two into a folder, the way phone home screens
	 * do; dropping near an edge inserts between rows instead. Without it,
	 * "drag one tab onto another" would have to mean either grouping or
	 * reordering, and the other would need a menu.
	 */
	static class Drop
	{
		private final StripRow row;
		private final DropZone zone;

		Drop(StripRow row, DropZone zone)
		{
			this.row = row;
			this.zone = zone;
		}

		StripRow getRow()
		{
			return row;
		}

		DropZone getZone()
		{
			return zone;
		}
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	void init()
	{
		if (initialised)
		{
			return;
		}

		parent = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (parent == null)
		{
			return;
		}

		Widget[] existing = parent.getChildren();
		baseChildCount = existing == null ? 0 : existing.length;

		int width = TAB_WIDTH;

		// Not really a text widget -- it exists only to catch the scroll wheel
		// over the column, exactly as core's does.
		scrollComponent = parent.createChild(-1, WidgetType.TEXT);
		scrollComponent.setHasListener(true);
		scrollComponent.setNoScrollThrough(true);
		scrollComponent.setOnScrollWheelListener((JavaScriptCallback) event -> scrollBy(event.getMouseY()));

		upButton = createGraphic("", TabSprites.UP_ARROW.getSpriteId(), -1, width, BUTTON_HEIGHT, MARGIN, 0);
		upButton.setAction(1, SCROLL_UP);
		upButton.setClickMask(upButton.getClickMask() | WidgetConfig.DRAG_ON);
		upButton.setHasListener(true);
		upButton.setOnOpListener((JavaScriptCallback) event -> scrollBy(-1));

		downButton = createGraphic("", TabSprites.DOWN_ARROW.getSpriteId(), -1, width, BUTTON_HEIGHT, MARGIN, 0);
		downButton.setAction(1, SCROLL_DOWN);
		downButton.setClickMask(downButton.getClickMask() | WidgetConfig.DRAG_ON);
		downButton.setHasListener(true);
		downButton.setOnOpListener((JavaScriptCallback) event -> scrollBy(1));

		newTab = createGraphic("", TabSprites.NEW_TAB.getSpriteId(), -1, width, NEW_TAB_HEIGHT, MARGIN, 0);
		newTab.setHasListener(true);
		newTab.setAction(NEWTAB_OP_NEW_TAB, "New tag tab");
		newTab.setAction(NEWTAB_OP_IMPORT_TAB, "Import tag tab");
		newTab.setAction(NEWTAB_OP_OPEN_TAB_MENU, "View tag tabs");
		newTab.setAction(NEWTAB_OP_NEW_FOLDER, "New folder");
		newTab.setOnOpListener((JavaScriptCallback) event -> actions.get().onNewTabOp(event.getOp() - 1));

		tree.load();
		initialised = true;
	}

	/**
	 * Whether the widgets in the container are still the ones we put there.
	 * <p>
	 * The core strip's {@code deinit()} calls {@code deleteAllChildren()} on the
	 * same container, so toggling its setting -- or any other plugin rebuilding
	 * the bank -- can pull our widgets out from under us while we still think we
	 * are set up. Verified by identity rather than by counting, because a count
	 * can match by coincidence.
	 */
	private boolean ownsWidgets()
	{
		if (parent == null || scrollComponent == null || parent != client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER))
		{
			return false;
		}

		Widget[] children = parent.getChildren();
		return children != null
			&& children.length > baseChildCount
			&& children[baseChildCount] == scrollComponent;
	}

	void deinit()
	{
		// Only cut back what is still ours. Truncating a container something
		// else has since rebuilt would delete that plugin's widgets instead.
		if (parent != null && ownsWidgets())
		{
			truncateChildren(baseChildCount);
		}

		parent = scrollComponent = upButton = downButton = newTab = null;
		initialised = false;
		rendered.clear();
		rowByWidget.clear();
		rows = new ArrayList<>();
		scrollRow = 0;
		// Not hidden first: the bank is closing and will rebuild these widgets
		// itself, and they may already be gone.
		overviewIcons.clear();
		pendingReveal = null;
	}

	/** Cut our widgets back off, leaving anything that was there before us. */
	private void truncateChildren(int keep)
	{
		Widget[] children = parent.getChildren();
		if (children == null)
		{
			return;
		}
		if (keep <= 0)
		{
			parent.deleteAllChildren();
			return;
		}
		if (children.length > keep)
		{
			parent.setChildren(Arrays.copyOf(children, keep));
		}
	}

	// ------------------------------------------------------------------
	// Building
	// ------------------------------------------------------------------

	/**
	 * Rebuild everything.
	 * <p>
	 * Wrapped end to end: a layout mistake should cost the player their tag
	 * column for a frame, never their bank.
	 */
	void rebuild()
	{
		try
		{
			// Our widgets can be destroyed underneath us -- the core strip's
			// deinit deletes every child of this container. Rebuilding onto
			// dead references is what makes toggling either plugin leave a
			// half-drawn column behind, so start over instead.
			if (initialised && !ownsWidgets())
			{
				deinit();
			}

			if (!initialised)
			{
				init();
			}
			if (!initialised)
			{
				return;
			}
			repositionButtons();
			rebuildRows();
		}
		catch (Exception e)
		{
			log.warn("Tag column rebuild failed; leaving the bank alone", e);
		}
	}

	/**
	 * Reposition without rebuilding.
	 * <p>
	 * Used for bank resizes and the storage popup, where the rows themselves are
	 * unchanged and only the space they have has moved.
	 */
	void relayout()
	{
		try
		{
			if (initialised)
			{
				repositionButtons();
				layoutRows();
			}
		}
		catch (Exception e)
		{
			log.warn("Tag column relayout failed", e);
		}
	}

	/**
	 * Position the fixed furniture and work out how much height the rows get.
	 * <p>
	 * Ported from core's {@code repositionButtons}, including the clamping
	 * against the incinerator, the collapse button and the storage popup tab --
	 * without which the column runs underneath them.
	 */
	private void repositionButtons()
	{
		int height = parent.getHeight() - BANK_BOTTOM_OFFSET;

		Widget widget = client.getWidget(InterfaceID.Bankmain.INCINERATOR_TARGET);
		if (widget != null && !widget.isHidden())
		{
			height = Math.min(height, widget.getRelativeY());
		}

		widget = client.getWidget(InterfaceID.Bankmain.POPUP_BUTTON_OUT);
		if (widget != null && !widget.isHidden())
		{
			height = Math.min(height, widget.getRelativeY());
		}

		Widget popupTab = client.getWidget(InterfaceID.Bankmain.STORAGE_POPUP_TAB);
		if (popupTab != null && !popupTab.isHidden())
		{
			widget = client.getWidget(InterfaceID.Bankmain.GIM_STORAGE);
			if (widget != null && !widget.isHidden())
			{
				height = Math.min(height, popupTab.getRelativeY() + widget.getRelativeY());
			}

			widget = client.getWidget(InterfaceID.Bankmain.POTIONSTORE_BUTTON);
			if (widget != null && !widget.isHidden())
			{
				height = Math.min(height, popupTab.getRelativeY() + widget.getRelativeY());
			}
		}

		int width = TAB_WIDTH;

		newTab.setOriginalWidth(width);
		newTab.revalidate();

		scrollComponent.setOriginalY(NEW_TAB_HEIGHT + 2 + BUTTON_HEIGHT);
		scrollComponent.setOriginalWidth(width + MARGIN * 2);

		availableHeight = Math.max(0, height - scrollComponent.getOriginalY() - BUTTON_HEIGHT);
		scrollComponent.setOriginalHeight(availableHeight);
		scrollComponent.revalidate();

		upButton.setOriginalY(NEW_TAB_HEIGHT + 2);
		upButton.setOriginalWidth(width);
		upButton.revalidate();

		downButton.setOriginalY(scrollComponent.getOriginalY() + availableHeight + MARGIN);
		downButton.setOriginalWidth(width);
		downButton.revalidate();
	}

	private void rebuildRows()
	{
		truncateChildren(baseChildCount + 4);
		rendered.clear();
		rowByWidget.clear();

		// Before anything is drawn: other plugins ask the core TabManager what
		// tabs exist, and with the core strip off nothing else fills it in.
		core.syncTabManager();

		tree.reconcile(core.tagTabs());
		if (pendingReveal != null)
		{
			// Expanded before the rows are flattened, so the tag is actually
			// among them and can be scrolled to below.
			tree.reveal(pendingReveal);
		}
		rows = tree.rows();

		int width = TAB_WIDTH;
		for (StripRow row : rows)
		{
			rendered.add(row.isFolder() ? buildFolderRow(row, width) : buildTagRow(row, width));
		}

		layoutRows();

		if (pendingReveal != null)
		{
			scrollToTag(pendingReveal);
			pendingReveal = null;
		}
	}

	private RenderedRow buildTagRow(StripRow row, int width)
	{
		String tag = row.getTag();
		int height = rowHeight();
		String name = ColorUtil.wrapWithColorTag(tag, HILIGHT_COLOR);
		boolean active = tag.equals(core.activeTag());

		RenderedRow out = new RenderedRow(row, height);

		Widget background = createGraphic(name,
			(active ? TabSprites.TAB_BACKGROUND_ACTIVE : TabSprites.TAB_BACKGROUND).getSpriteId(),
			-1, width, height, MARGIN, -1);
		background.setSpriteTiling(true);
		addTabActions(row, background);
		addDragOptions(background);
		out.pieces.add(new Piece(background, 0));
		rowByWidget.put(background, row);

		// Membership is shown by colour rather than by indenting the row. An
		// indent deep enough to read also pushed the 36px item sprite off the
		// right of a 39px column.
		if (row.isNested())
		{
			// Lighter over the active tab, which would otherwise stop looking
			// active once it is tinted.
			int opacity = Math.min(255, opacityOf(config.tagTintStrength()) + (active ? 60 : 0));
			out.pieces.add(new Piece(
				fill(row.getParent().getColor(), opacity, MARGIN, width, height), 0));
		}

		int iconDy = Math.max(0, (height - Constants.ITEM_SPRITE_HEIGHT) / 2);
		// Only the background carries the menu options. The icon sits on top of
		// it and the two overlap, so giving both the same options makes the
		// client collect every entry twice. Clicks land on the background
		// underneath, which is how the stock strip does it too.
		Widget icon = createGraphic(name, -1, core.iconFor(tag),
			Constants.ITEM_SPRITE_WIDTH, Constants.ITEM_SPRITE_HEIGHT, MARGIN + 3, -1);
		addDragOptions(icon);
		out.pieces.add(new Piece(icon, iconDy));
		rowByWidget.put(icon, row);

		// No name is drawn: the game already announces it in the hover text at
		// the top left ("View tag tab potions"), and a text widget does not clip
		// to its own bounds, so anything longer than the column ran over the
		// bank items.
		return out;
	}

	private RenderedRow buildFolderRow(StripRow row, int width)
	{
		Folder folder = row.getFolder();
		int height = rowHeight();
		String name = ColorUtil.wrapWithColorTag(folder.getName(), HILIGHT_COLOR);

		RenderedRow out = new RenderedRow(row, height);

		Widget background = fill(folder.getColor(), opacityOf(config.folderTintStrength()), MARGIN, width, height);
		background.setName(name);
		background.setAction(FOLDER_OP_TOGGLE, folder.isCollapsed() ? "Expand" : "Collapse");
		background.setAction(FOLDER_OP_CHANGE_ICON, "Change icon");
		background.setAction(FOLDER_OP_SET_COLOUR, "Set colour");
		background.setAction(FOLDER_OP_RENAME, "Rename folder");
		background.setAction(FOLDER_OP_DELETE, "Delete folder");
		background.setHasListener(true);
		background.setOnOpListener((JavaScriptCallback) event ->
			actions.get().onFolderOp(event.getOp() - 1, row));
		addDragOptions(background);
		out.pieces.add(new Piece(background, 0));
		rowByWidget.put(background, row);

		// Item sprites are transparent around the item, so the folder's colour
		// still reads through from the rectangle behind. Drawn at its native
		// size: scaling one down is a nearest-neighbour resample and looks
		// visibly broken.
		int icon = folderIcon(folder);
		if (icon > 0)
		{
			Widget item = createGraphic(name, -1, icon,
				Constants.ITEM_SPRITE_WIDTH, Constants.ITEM_SPRITE_HEIGHT, MARGIN + 3, -1);
			out.pieces.add(new Piece(item, (height - Constants.ITEM_SPRITE_HEIGHT) / 2));
		}

		// A plain "+"/"-" rather than a chevron: the game fonts do not carry the
		// arrow codepoints, and a missing glyph reads as an empty row. Badged
		// into the corner when there is an icon under it.
		Widget marker = parent.createChild(-1, WidgetType.TEXT);
		marker.setText(folder.isCollapsed() ? "+" : "-");
		marker.setFontId(FontID.BOLD_12);
		marker.setTextColor(FOLDER_TEXT);
		marker.setTextShadowed(true);
		marker.setOriginalX(MARGIN + 2);
		marker.setOriginalY(-1);
		marker.setOriginalWidth(10);
		marker.setOriginalHeight(icon > 0 ? 12 : height);
		marker.setXTextAlignment(WidgetTextAlignment.LEFT);
		marker.setYTextAlignment(WidgetTextAlignment.CENTER);
		marker.revalidate();
		out.pieces.add(new Piece(marker, 0));

		return out;
	}

	/**
	 * A folder with no icon of its own borrows its first member's, so a folder
	 * made by dragging two tabs together is never a blank bar.
	 */
	private int folderIcon(Folder folder)
	{
		if (folder.getIconItemId() > 0)
		{
			return folder.getIconItemId();
		}
		return folder.getTags().isEmpty() ? 0 : core.iconFor(folder.getTags().get(0));
	}

	private void addTabActions(StripRow row, Widget w)
	{
		w.setAction(TAB_OP_OPEN_TAG, "View tag tab");
		w.setAction(TAB_OP_CHANGE_ICON, "Change icon");
		w.setAction(TAB_OP_LAYOUT, core.hasLayout(row.getTag()) ? "Disable layout" : "Enable layout");
		w.setAction(TAB_OP_EXPORT_TAB, "Export tag tab");
		w.setAction(TAB_OP_RENAME_TAB, "Rename tag tab");
		w.setAction(TAB_OP_DELETE_TAB, "Delete tag tab");
		if (row.isNested())
		{
			w.setAction(TAB_OP_UNFILE, "Remove from folder");
		}
		w.setHasListener(true);
		w.setOnOpListener((JavaScriptCallback) event -> actions.get().onTagOp(event.getOp() - 1, row));
	}

	/**
	 * Make a widget both draggable and a drop target.
	 * <p>
	 * The quantity settings are core's: an item sprite with no quantity drawn
	 * still needs a non-zero quantity to render at all.
	 */
	private void addDragOptions(Widget w)
	{
		w.setClickMask(w.getClickMask() | WidgetConfig.DRAG | WidgetConfig.DRAG_ON);
		w.setDragDeadTime(5);
		w.setDragDeadZone(5);
		w.setItemQuantity(10000);
		w.setItemQuantityMode(ItemQuantityMode.NEVER);
	}

	/** A filled rectangle in the given colour, used for folder and member tint. */
	private Widget fill(int rgb, int opacity, int x, int width, int height)
	{
		Widget w = parent.createChild(-1, WidgetType.RECTANGLE);
		w.setFilled(true);
		w.setTextColor(rgb);
		w.setOpacity(opacity);
		w.setOriginalX(x);
		w.setOriginalY(-1);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
		w.revalidate();
		return w;
	}

	/**
	 * Clamped rather than trusted. A value stored while the allowed range was
	 * wider would otherwise keep rendering out of range until someone reopened
	 * the setting.
	 */
	private int rowHeight()
	{
		return Math.max(24, Math.min(BankTagFoldersConfig.MAX_ROW_HEIGHT, config.tagRowHeight()));
	}

	/**
	 * Config gives strength as a percentage of colour; widgets want the
	 * opposite, where 0 is solid and 255 is invisible.
	 */
	private static int opacityOf(int strength)
	{
		int clamped = Math.max(0, Math.min(100, strength));
		return 255 - (clamped * 255 / 100);
	}

	private Widget createGraphic(String name, int spriteId, int itemId, int width, int height, int x, int y)
	{
		Widget widget = parent.createChild(-1, WidgetType.GRAPHIC);
		widget.setOriginalWidth(width);
		widget.setOriginalHeight(height);
		widget.setOriginalX(x);
		widget.setOriginalY(y);
		widget.setSpriteId(spriteId);

		if (itemId > 0)
		{
			widget.setItemId(itemId);
			widget.setItemQuantity(-1);
			widget.setBorderType(1);
		}

		widget.setName(name);
		widget.revalidate();
		return widget;
	}

	// ------------------------------------------------------------------
	// Layout and scrolling
	// ------------------------------------------------------------------

	/** Position the visible window of rows; everything else is hidden. */
	void layoutRows()
	{
		if (!initialised)
		{
			return;
		}

		Widget dragged = client.getDraggedWidget();
		for (RenderedRow row : rendered)
		{
			row.top = -1;
			for (Piece piece : row.pieces)
			{
				// Hiding the widget being dragged makes it vanish mid-drag when
				// the column re-lays because of a scroll.
				if (piece.widget != dragged)
				{
					piece.widget.setHidden(true);
				}
			}
		}

		clampScroll();

		int y = scrollComponent.getOriginalY() + MARGIN;
		int used = 0;
		for (int i = scrollRow; i < rendered.size(); i++)
		{
			RenderedRow row = rendered.get(i);
			if (used + row.height > availableHeight)
			{
				break;
			}

			row.top = y;
			for (Piece piece : row.pieces)
			{
				piece.widget.setOriginalY(y + piece.dy);
				piece.widget.setHidden(false);
				piece.widget.revalidate();
			}

			y += row.height + MARGIN;
			used += row.height + MARGIN;
		}
	}

	/**
	 * Pull the scroll position back so the column always ends up full.
	 * <p>
	 * Collapsing a folder can shorten the list under the current offset, and
	 * without this the player is left staring at a mostly empty strip with no
	 * obvious way back.
	 */
	private void clampScroll()
	{
		if (scrollRow <= 0)
		{
			scrollRow = 0;
			return;
		}

		int used = 0;
		int first = rendered.size();
		for (int i = rendered.size() - 1; i >= 0; i--)
		{
			int height = rendered.get(i).height;
			if (used + height > availableHeight)
			{
				break;
			}
			used += height + MARGIN;
			first = i;
		}
		scrollRow = Math.min(scrollRow, first);
	}

	/** Ask for a tag to be revealed and scrolled to when the column next builds. */
	void requestReveal(String tag)
	{
		pendingReveal = tag;
	}

	/** Scroll a tag into view, if it did not already land on screen. */
	private void scrollToTag(String tag)
	{
		for (int i = 0; i < rendered.size(); i++)
		{
			RenderedRow row = rendered.get(i);
			if (row.row.isTag() && tag.equals(row.row.getTag()))
			{
				if (row.top < 0)
				{
					scrollRow = i;
					layoutRows();
				}
				return;
			}
		}
	}

	void scrollBy(int delta)
	{
		if (!initialised || delta == 0)
		{
			return;
		}

		int next = scrollRow + delta;
		if (next < 0)
		{
			next = 0;
		}
		if (next >= rendered.size())
		{
			next = Math.max(0, rendered.size() - 1);
		}

		scrollRow = next;
		layoutRows();
	}

	/** Rate-limited scroll, so holding a drag over an arrow does not race. */
	void scrollTick(int direction)
	{
		if (startScroll.until(Instant.now(), ChronoUnit.MILLIS) >= SCROLL_TICK)
		{
			startScroll = Instant.now();
			scrollBy(direction);
		}
	}

	// ------------------------------------------------------------------
	// Tag tab overview ("View tag tabs")
	// ------------------------------------------------------------------

	/**
	 * Draw every tab as an item icon in the bank area.
	 * <p>
	 * Ported from core: the icons are appended to the bank's own item container
	 * rather than given their own layer, because a new layer would mean
	 * recomputing the bank scrollbar.
	 *
	 * @return the height the bank's scroll area should be given
	 */
	int rebuildOverview(boolean overviewActive)
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null)
		{
			return 0;
		}

		// The bank discards and rebuilds its item widgets, which takes ours with
		// them; a stale reference would be hidden or written to at random.
		for (Widget icon : overviewIcons)
		{
			if (icon == null || icon.getParent() != items)
			{
				overviewIcons.clear();
				break;
			}
		}

		// Hide only what we made. The previous approach -- hide every child past
		// a remembered index -- hid *real bank items* whenever that index was
		// captured while the bank was filtered to a tag. The bank computes its
		// scroll height from the items still visible, so it came out at zero and
		// everything below the first screenful became unreachable.
		for (Widget icon : overviewIcons)
		{
			icon.setHidden(true);
		}

		if (!overviewActive)
		{
			return 0;
		}

		int itemX = BankTagsPlugin.BANK_ITEM_START_X;
		int itemY = BankTagsPlugin.BANK_ITEM_START_Y;
		int rowIndex = 0;
		int used = 0;

		for (String tag : core.tagTabs())
		{
			Widget menu;
			if (used < overviewIcons.size())
			{
				menu = overviewIcons.get(used);
			}
			else
			{
				menu = items.createChild(-1, WidgetType.GRAPHIC);
				menu.setOriginalWidth(BankTagsPlugin.BANK_ITEM_WIDTH);
				menu.setOriginalHeight(BankTagsPlugin.BANK_ITEM_HEIGHT);
				overviewIcons.add(menu);
			}
			used++;

			StripRow row = StripRow.tag(tag, tree.folderOf(tag));
			menu.setHidden(false);
			menu.setOriginalX(itemX);
			menu.setOriginalY(itemY);
			menu.setName(ColorUtil.wrapWithColorTag(tag, HILIGHT_COLOR));
			menu.setItemId(core.iconFor(tag));
			menu.setItemQuantity(-1);
			menu.setBorderType(1);
			addTabActions(row, menu);
			addDragOptions(menu);
			menu.revalidate();

			rowIndex++;
			if (rowIndex == BankTagsPlugin.BANK_ITEMS_PER_ROW)
			{
				itemX = BankTagsPlugin.BANK_ITEM_START_X;
				itemY += BankTagsPlugin.BANK_ITEM_Y_PADDING + BankTagsPlugin.BANK_ITEM_HEIGHT;
				rowIndex = 0;
			}
			else
			{
				itemX += BankTagsPlugin.BANK_ITEM_X_PADDING + BankTagsPlugin.BANK_ITEM_WIDTH;
			}
		}

		return itemY + BankTagsPlugin.BANK_ITEM_HEIGHT;
	}

	/** Hide the real bank items so the overview has the area to itself. */
	void hideBank()
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null || items.getChildren() == null)
		{
			return;
		}
		for (Widget w : items.getChildren())
		{
			if (w != null)
			{
				w.setHidden(true);
			}
		}
	}

	// ------------------------------------------------------------------
	// Hit testing, for drops
	// ------------------------------------------------------------------

	/** The row a widget stands for, or null when the widget is not one of ours. */
	@Nullable
	StripRow rowFor(@Nullable Widget widget)
	{
		return widget == null ? null : rowByWidget.get(widget);
	}

	boolean isColumnWidget(@Nullable Widget widget)
	{
		return widget != null && parent != null && widget.getParent() == parent;
	}

	/** Whether a point on the canvas falls inside our column. */
	boolean containsCanvasPoint(@Nullable Point point)
	{
		if (point == null || parent == null || parent.isHidden())
		{
			return false;
		}

		java.awt.Rectangle bounds = parent.getBounds();
		return point.getX() >= bounds.x
			&& point.getX() <= bounds.x + MARGIN + TAB_WIDTH
			&& point.getY() >= bounds.y
			&& point.getY() <= bounds.y + bounds.height;
	}

	/**
	 * Resolve a drop point to a row and a zone.
	 * <p>
	 * The outer quarter at each end of a row inserts beside it; the middle half
	 * drops into it. A quarter of a 40px tab is 10px, which is enough to hit
	 * deliberately without being easy to hit by accident.
	 */
	@Nullable
	Drop dropAt(@Nullable Point point)
	{
		if (point == null || parent == null || !initialised)
		{
			return null;
		}

		int local = point.getY() - parent.getBounds().y;

		for (RenderedRow row : rendered)
		{
			if (row.top < 0 || local < row.top || local >= row.top + row.height)
			{
				continue;
			}

			int offset = local - row.top;
			int edge = Math.max(4, row.height / 4);
			if (offset < edge)
			{
				return new Drop(row.row, DropZone.BEFORE);
			}
			if (offset >= row.height - edge)
			{
				return new Drop(row.row, DropZone.AFTER);
			}
			return new Drop(row.row, DropZone.INTO);
		}

		// Below the last row but still inside the column: append at the end.
		return null;
	}
}
