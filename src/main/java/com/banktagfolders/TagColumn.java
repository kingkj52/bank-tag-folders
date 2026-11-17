package com.banktagfolders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FontID;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfig;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * The tag column, rearranged into folders.
 * <p>
 * Bank Tags draws its own column and keeps doing so. It builds the strip as
 * dynamic children of {@code Bankmain.ITEMS_CONTAINER}: four fixed widgets (the
 * scroll catcher, the two arrows and the new-tab button) followed by a
 * background and an icon for every tab. After each of its rebuilds this class
 * hides the tabs inside closed folders, adds a header row for each folder, and
 * restacks what is left. Every tab stays Bank Tags' own widget, so renaming,
 * deleting, exporting, layouts, icons and dropping an item on a tab are still
 * its code doing it.
 * <p>
 * Core relays its tabs out by position in that child array from several places
 * that raise no event, so {@link #heal()} notices when it has done so and draws
 * again. Everything of ours is appended after core's widgets and removed again
 * before each redraw.
 */
@Slf4j
@Singleton
public class TagColumn
{
	private static final java.awt.Color HILIGHT_COLOR = JagexColors.MENU_TARGET;

	static final String SCROLL_UP = "Scroll up";
	static final String SCROLL_DOWN = "Scroll down";
	static final String OPTION_VIEW_TAG = "View tag tab";
	static final String OPTION_UNFILE = "Remove from folder";
	static final String OPTION_NEW_FOLDER = "New folder";

	private static final int TAB_WIDTH = 39;
	private static final int MARGIN = 1;
	/** Children core creates before any tab: scroll catcher, up, down, new tab. */
	private static final int CORE_BUTTONS = 4;
	/** Height core gives a tab, and the height of the sprite behind it. */
	private static final int NATIVE_TAB_HEIGHT = 40;
	private static final int MIN_ROW_HEIGHT = 24;
	/** Rate limit for hold-to-scroll on the arrow buttons, in milliseconds. */
	private static final int SCROLL_TICK = 500;
	private static final int HIDDEN = Integer.MIN_VALUE;

	/** Ours, registered on a tag's own background beside core's six. */
	static final int TAB_OP_UNFILE = 7;
	static final int NEWTAB_OP_NEW_FOLDER = 4;

	static final int FOLDER_OP_TOGGLE = 1;
	static final int FOLDER_OP_CHANGE_ICON = 2;
	static final int FOLDER_OP_SET_COLOUR = 3;
	static final int FOLDER_OP_RENAME = 4;
	static final int FOLDER_OP_DELETE = 5;

	private static final int FOLDER_TEXT = 0xFFFFFF;

	private final Client client;
	private final BankTagFoldersConfig config;
	private final FolderTree tree;
	private final CoreBankTags core;

	/**
	 * Lazily resolved to break the injection cycle: the actions ask for a redraw
	 * when they finish, and the column hands them clicks.
	 */
	private final Provider<ColumnActions> actions;

	private Widget parent;
	private Widget scroll;
	private Widget up;
	private Widget down;
	private Widget newTab;

	/** The scroll widget our listeners are on, so a fresh bank gets them again. */
	private Widget hooked;

	/** Every widget core made for a tab. */
	private final List<Widget> coreWidgets = new ArrayList<>();

	/** Widgets we added to the container, in creation order. Never core's. */
	private final List<Widget> created = new ArrayList<>();
	private final Set<Widget> ours = Collections.newSetFromMap(new IdentityHashMap<>());

	private final Pool headers = new Pool(WidgetType.RECTANGLE);
	private final Pool tints = new Pool(WidgetType.RECTANGLE);
	private final Pool items = new Pool(WidgetType.GRAPHIC);
	private final Pool markers = new Pool(WidgetType.TEXT);

	/** Where each widget should be after the last layout, or {@link #HIDDEN}. */
	private final Map<Widget, Integer> expectedY = new IdentityHashMap<>();

	private final Map<Widget, StripRow> rowByWidget = new IdentityHashMap<>();
	private final List<RenderedRow> rendered = new ArrayList<>();

	/** What the container looked like when we last drew, to notice core redrawing. */
	private Widget firstCore;
	@Nullable
	private Widget lastDragged;

	private int scrollRow;
	private int availableHeight;
	private Instant startScroll = Instant.now();

	/**
	 * Set on shutdown. Rows are then drawn as core would draw them, which is what
	 * the scroll listeners we cannot hand back keep drawing until the bank is
	 * reopened and core makes its own again.
	 */
	private boolean detached;

	/** Open the folder holding the active tag on the next draw, once per bank. */
	private boolean revealPending;

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

		/** The core widget this one only mimics, hidden while that one is carried. */
		@Nullable
		private final Widget mirrorOf;

		Piece(Widget widget, int dy, @Nullable Widget mirrorOf)
		{
			this.widget = widget;
			this.dy = dy;
			this.mirrorOf = mirrorOf;
		}
	}

	/**
	 * Widgets of one kind that we add to the container once and then reuse.
	 * <p>
	 * Never removed from the middle of the child array. A dynamic child's slot
	 * number is stored on the widget and is what a click is routed by, so taking
	 * ours out from in front of another plugin's widgets slides theirs down a
	 * slot without telling them, and their clicks land on the wrong widget.
	 * Reusing ours keeps every slot where it was.
	 */
	private final class Pool
	{
		private final int type;
		private final List<Widget> widgets = new ArrayList<>();
		private int used;

		Pool(int type)
		{
			this.type = type;
		}

		Widget next()
		{
			if (used < widgets.size())
			{
				return widgets.get(used++);
			}

			Widget widget = parent.createChild(-1, type);
			widgets.add(widget);
			created.add(widget);
			ours.add(widget);
			used++;
			return widget;
		}

		void reset()
		{
			used = 0;
		}

		void clear()
		{
			widgets.clear();
			used = 0;
		}
	}

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

	/** What core has drawn. */
	private static final class CoreColumn
	{
		private Widget parent;
		private Widget scroll;
		private Widget up;
		private Widget down;
		private Widget newTab;

		/** Core's widgets per tab, in the order it made them: background, then icon. */
		private final Map<String, List<Widget>> tabs = new LinkedHashMap<>();
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
	 * do; dropping near an edge inserts between rows instead.
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
	// Reading core's column
	// ------------------------------------------------------------------

	@Nullable
	private CoreColumn scan()
	{
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (container == null)
		{
			return null;
		}

		Widget[] children = container.getChildren();
		if (children == null || children.length < CORE_BUTTONS)
		{
			return null;
		}

		Widget[] buttons = Arrays.copyOf(children, CORE_BUTTONS);
		for (Widget button : buttons)
		{
			if (button == null)
			{
				return null;
			}
		}

		// Anything else in the container is not core's column: another plugin
		// may share it, and drawing into somebody else's widgets would be worse
		// than drawing nothing.
		if (!hasAction(buttons[1], SCROLL_UP) || !hasAction(buttons[2], SCROLL_DOWN))
		{
			return null;
		}

		CoreColumn column = new CoreColumn();
		column.parent = container;
		column.scroll = buttons[0];
		column.up = buttons[1];
		column.down = buttons[2];
		column.newTab = buttons[3];

		for (int i = CORE_BUTTONS; i < children.length; i++)
		{
			Widget child = children[i];
			if (child == null || ours.contains(child))
			{
				continue;
			}

			String name = child.getName();
			if (name == null || name.isEmpty())
			{
				continue;
			}

			String tag = Text.removeTags(name);
			if (!tag.isEmpty())
			{
				column.tabs.computeIfAbsent(tag, t -> new ArrayList<>(2)).add(child);
			}
		}

		return column;
	}

	/** Take away an option we added, without growing the array to say so. */
	private static void clearAction(Widget widget, int index)
	{
		String[] ops = widget.getActions();
		if (ops != null && ops.length > index && ops[index] != null)
		{
			widget.setAction(index, null);
		}
	}

	private static boolean hasAction(Widget widget, String option)
	{
		String[] ops = widget.getActions();
		if (ops == null)
		{
			return false;
		}
		for (String op : ops)
		{
			if (option.equals(op))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	/**
	 * Draw the column again from core's widgets.
	 * <p>
	 * Wrapped end to end: a layout mistake should cost the player their folders
	 * for a frame, never their bank.
	 */
	void rebuild()
	{
		try
		{
			draw();
		}
		catch (Exception e)
		{
			log.warn("Tag column rebuild failed; leaving the bank alone", e);
		}
	}

	private void draw()
	{
		CoreColumn column = scan();
		if (column == null)
		{
			forget();
			return;
		}

		parent = column.parent;
		scroll = column.scroll;
		up = column.up;
		down = column.down;
		newTab = column.newTab;

		prepare();
		hook();

		if (detached)
		{
			clearAction(newTab, NEWTAB_OP_NEW_FOLDER);
		}
		else
		{
			newTab.setAction(NEWTAB_OP_NEW_FOLDER, OPTION_NEW_FOLDER);
		}

		// The tab list in config is what core keeps current, and it is the only
		// thing that may delete a folder entry. An empty answer while core has
		// tabs drawn means we asked mid-rebuild, and must not read as "all gone".
		List<String> live = core.tagTabs();
		if (!live.isEmpty() || column.tabs.isEmpty())
		{
			tree.reconcile(live);
		}

		if (revealPending)
		{
			revealPending = false;
			String active = core.activeTag();
			if (active != null)
			{
				// Expanded before the rows are flattened, so the tag is actually
				// among them and can be scrolled to below.
				tree.reveal(active);
				pendingReveal = active;
			}
		}

		List<StripRow> rows = detached ? flat(column) : tree.rows();
		int height = detached ? NATIVE_TAB_HEIGHT : rowHeight();

		for (List<Widget> widgets : column.tabs.values())
		{
			coreWidgets.addAll(widgets);
		}

		for (StripRow row : rows)
		{
			RenderedRow built = row.isFolder()
				? buildFolderRow(row, height, column)
				: buildTagRow(row, height, column.tabs.get(row.getTag()));
			if (built != null)
			{
				rendered.add(built);
			}
		}

		layoutRows();

		if (pendingReveal != null)
		{
			scrollToTag(pendingReveal);
			pendingReveal = null;
		}

		remember();
	}

	private static List<StripRow> flat(CoreColumn column)
	{
		List<StripRow> rows = new ArrayList<>();
		for (String tag : column.tabs.keySet())
		{
			rows.add(StripRow.tag(tag, null));
		}
		return rows;
	}

	/**
	 * Get ready to draw into the widgets we already have.
	 * <p>
	 * If core has rebuilt its tabs since the last draw, it truncated the child
	 * array and took ours with it, so they are forgotten and made again. Otherwise
	 * they are still attached, in the same slots, and are reused.
	 */
	private void prepare()
	{
		Widget[] children = parent.getChildren();
		boolean intact = !created.isEmpty() && children != null
			&& attachedAt(children, created.get(0))
			&& attachedAt(children, created.get(created.size() - 1));

		if (!intact)
		{
			created.clear();
			ours.clear();
			headers.clear();
			tints.clear();
			items.clear();
			markers.clear();
		}

		headers.reset();
		tints.reset();
		items.reset();
		markers.reset();

		rowByWidget.clear();
		rendered.clear();
		coreWidgets.clear();
		expectedY.clear();
	}

	private static boolean attachedAt(Widget[] children, Widget widget)
	{
		int index = widget.getIndex();
		return index >= 0 && index < children.length && children[index] == widget;
	}

	/**
	 * Take over scrolling. Core scrolls by an index into its flat tab list, which
	 * is not what is drawn once folders are closed, so its arithmetic lands on the
	 * wrong row. Replacing the listeners on the widgets it already made beats
	 * adding a second set of arrows.
	 */
	private void hook()
	{
		if (scroll == hooked)
		{
			return;
		}
		hooked = scroll;

		scroll.setOnScrollWheelListener((JavaScriptCallback) event -> scrollBy(event.getMouseY()));
		up.setOnOpListener((JavaScriptCallback) event -> scrollBy(-1));
		down.setOnOpListener((JavaScriptCallback) event -> scrollBy(1));
	}

	private void remember()
	{
		Widget[] children = parent.getChildren();
		firstCore = children != null && children.length > CORE_BUTTONS ? children[CORE_BUTTONS] : null;
	}

	/**
	 * Redraw if core has rebuilt or relaid its column behind our back.
	 * <p>
	 * Core rebuilds its tabs from inside its own option handlers (delete, rename,
	 * import) and relays them out when the bank is resized, none of which raises
	 * an event we hear. Both are cheap to spot: a rebuild replaces the widgets,
	 * and a relayout moves or unhides ones we have placed. Two reads per widget
	 * when there is nothing to do.
	 */
	void heal()
	{
		Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
		if (container == null)
		{
			return;
		}

		Widget[] children = container.getChildren();
		if (children == null || children.length <= CORE_BUTTONS)
		{
			return;
		}

		if (!attached(children))
		{
			rebuild();
		}
		else if (!placed())
		{
			layoutRows();
		}
	}

	private boolean attached(Widget[] children)
	{
		// Other plugins add widgets to this container too, so the count is no
		// guide. Core's widgets still being the ones we drew, and ours still
		// sitting in their slots, is.
		if (firstCore == null || scroll == null || children.length <= CORE_BUTTONS)
		{
			return false;
		}
		if (children[0] != scroll || children[CORE_BUTTONS] != firstCore)
		{
			return false;
		}
		return created.isEmpty()
			|| attachedAt(children, created.get(0)) && attachedAt(children, created.get(created.size() - 1));
	}

	private boolean placed()
	{
		Widget dragged = client.getDraggedWidget();
		if (dragged != lastDragged)
		{
			return false;
		}

		for (Map.Entry<Widget, Integer> entry : expectedY.entrySet())
		{
			Widget widget = entry.getKey();
			if (widget == dragged)
			{
				continue;
			}

			int y = entry.getValue();
			boolean hidden = widget.isSelfHidden();
			if (y == HIDDEN ? !hidden : hidden || widget.getOriginalY() != y)
			{
				return false;
			}
		}
		return true;
	}

	/** Forget everything: the bank closed, or core's column is gone. */
	void forget()
	{
		parent = scroll = up = down = newTab = hooked = null;
		firstCore = null;
		lastDragged = null;
		// Our widgets are kept: if they are still attached the next draw reuses
		// them, and if core has dropped them it finds that out then.
		rowByWidget.clear();
		rendered.clear();
		coreWidgets.clear();
		expectedY.clear();
	}

	void onBankClosed()
	{
		forget();
		scrollRow = 0;
		revealPending = false;
		pendingReveal = null;
	}

	/** Ask for the active tag's folder to be opened when the column next builds. */
	void requestReveal()
	{
		revealPending = true;
	}

	/**
	 * Put the column back as core drew it and stop interfering.
	 * <p>
	 * Core's scroll listeners were lambdas we replaced and cannot be handed back,
	 * so the column is left drawing core's flat list, which is also what they
	 * will keep drawing until the bank is reopened and core makes its own again.
	 */
	void release()
	{
		detached = true;
		rebuild();
	}

	void attach()
	{
		detached = false;
	}

	// ------------------------------------------------------------------
	// Building rows
	// ------------------------------------------------------------------

	@Nullable
	private RenderedRow buildTagRow(StripRow row, int height, @Nullable List<Widget> widgets)
	{
		if (widgets == null || widgets.isEmpty())
		{
			return null;
		}

		Widget background = widgets.get(0);
		Widget icon = widgets.size() > 1 ? widgets.get(1) : null;
		boolean tinted = row.isNested() && !detached;

		RenderedRow out = new RenderedRow(row, height);

		background.setSpriteTiling(true);
		background.setOriginalHeight(height);
		// Only the background carries menu options, as in core: the icon sits on
		// top of it and the two overlap, so options on both would be listed twice.
		if (tinted)
		{
			background.setAction(TAB_OP_UNFILE, OPTION_UNFILE);
		}
		else
		{
			clearAction(background, TAB_OP_UNFILE);
		}
		addDragOptions(background);
		out.pieces.add(new Piece(background, 0, null));
		rowByWidget.put(background, row);

		if (tinted)
		{
			// Lighter over the active tab, which would otherwise stop looking
			// active once it is tinted. Membership is shown by colour rather than
			// by indenting: the column is 39px wide and an item sprite is 36.
			boolean active = row.getTag().equals(core.activeTag());
			int opacity = Math.min(255, opacityOf(config.tagTintStrength()) + (active ? 60 : 0));
			out.pieces.add(new Piece(rectangle(tints.next(), row.getParent().getColor(), opacity, height), 0, null));
		}

		if (icon != null)
		{
			int iconDy = Math.max(0, (height - Constants.ITEM_SPRITE_HEIGHT) / 2);
			out.pieces.add(new Piece(icon, iconDy, null));
			rowByWidget.put(icon, row);

			if (tinted && icon.getItemId() > 0)
			{
				// The tint is a rectangle over core's widgets, which would wash out
				// the icon underneath. Drawn again on top, with nothing to click, so
				// the real icon below still takes the clicks and the drag.
				out.pieces.add(new Piece(item(items.next(), icon.getName(), icon.getItemId(), MARGIN + 3), iconDy, icon));
			}
		}

		return out;
	}

	private RenderedRow buildFolderRow(StripRow row, int height, CoreColumn column)
	{
		Folder folder = row.getFolder();
		String name = ColorUtil.wrapWithColorTag(folder.getName(), HILIGHT_COLOR);

		RenderedRow out = new RenderedRow(row, height);

		Widget background = rectangle(headers.next(), folder.getColor(), opacityOf(config.folderTintStrength()), height);
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
		out.pieces.add(new Piece(background, 0, null));
		rowByWidget.put(background, row);

		// Item sprites are transparent around the item, so the folder's colour
		// still reads through from the rectangle behind. Drawn at its native
		// size: scaling one down is a nearest-neighbour resample and looks
		// visibly broken.
		int icon = folderIcon(folder, column);
		if (icon > 0)
		{
			Widget sprite = item(items.next(), name, icon, MARGIN + 3);
			out.pieces.add(new Piece(sprite, Math.max(0, (height - Constants.ITEM_SPRITE_HEIGHT) / 2), null));
		}

		// A plain "+"/"-" rather than a chevron: the game fonts do not carry the
		// arrow codepoints, and a missing glyph reads as an empty row. Badged
		// into the corner when there is an icon under it.
		Widget marker = markers.next();
		marker.setText(folder.isCollapsed() ? "+" : "-");
		marker.setFontId(FontID.BOLD_12);
		marker.setTextColor(FOLDER_TEXT);
		marker.setTextShadowed(true);
		marker.setOriginalX(MARGIN + 2);
		marker.setOriginalWidth(10);
		marker.setOriginalHeight(icon > 0 ? 12 : height);
		marker.setXTextAlignment(WidgetTextAlignment.LEFT);
		marker.setYTextAlignment(WidgetTextAlignment.CENTER);
		marker.revalidate();
		out.pieces.add(new Piece(marker, 0, null));

		return out;
	}

	/**
	 * A folder with no icon of its own borrows its first member's, so a folder
	 * made by dragging two tabs together is never a blank bar.
	 */
	private static int folderIcon(Folder folder, CoreColumn column)
	{
		if (folder.getIconItemId() > 0)
		{
			return folder.getIconItemId();
		}

		for (String tag : folder.getTags())
		{
			List<Widget> widgets = column.tabs.get(tag);
			if (widgets != null && widgets.size() > 1 && widgets.get(1).getItemId() > 0)
			{
				return widgets.get(1).getItemId();
			}
		}
		return 0;
	}

	/**
	 * Make a widget both draggable and a drop target.
	 * <p>
	 * Core only does this to the icon. Doing it to the background too means an
	 * item dropped anywhere on a tab tags it, and the tab can be picked up by any
	 * part of it.
	 */
	private static void addDragOptions(Widget w)
	{
		w.setClickMask(w.getClickMask() | WidgetConfig.DRAG | WidgetConfig.DRAG_ON);
		w.setDragDeadTime(5);
		w.setDragDeadZone(5);
	}

	/** A filled rectangle in the given colour, used for folder and member tint. */
	private static Widget rectangle(Widget w, int rgb, int opacity, int height)
	{
		w.setFilled(true);
		w.setTextColor(rgb);
		w.setOpacity(opacity);
		w.setOriginalX(MARGIN);
		w.setOriginalY(-1);
		w.setOriginalWidth(TAB_WIDTH);
		w.setOriginalHeight(height);
		w.revalidate();
		return w;
	}

	private static Widget item(Widget w, String name, int itemId, int x)
	{
		w.setSpriteId(-1);
		w.setItemId(itemId);
		w.setItemQuantity(-1);
		w.setBorderType(1);
		w.setName(name);
		w.setOriginalX(x);
		w.setOriginalY(-1);
		w.setOriginalWidth(Constants.ITEM_SPRITE_WIDTH);
		w.setOriginalHeight(Constants.ITEM_SPRITE_HEIGHT);
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
		return Math.max(MIN_ROW_HEIGHT, Math.min(BankTagFoldersConfig.MAX_ROW_HEIGHT, config.tagRowHeight()));
	}

	/**
	 * Config gives strength as a percentage of colour; widgets want the
	 * opposite, where 0 is solid and 255 is invisible.
	 */
	static int opacityOf(int strength)
	{
		int clamped = Math.max(0, Math.min(100, strength));
		return 255 - (clamped * 255 / 100);
	}

	// ------------------------------------------------------------------
	// Layout and scrolling
	// ------------------------------------------------------------------

	/** Position the visible window of rows; everything else is hidden. */
	void layoutRows()
	{
		if (parent == null || scroll == null)
		{
			return;
		}

		Widget dragged = client.getDraggedWidget();
		lastDragged = dragged;
		expectedY.clear();

		for (Widget widget : coreWidgets)
		{
			hide(widget, dragged);
		}
		for (Widget widget : created)
		{
			hide(widget, dragged);
		}
		for (RenderedRow row : rendered)
		{
			row.top = -1;
		}

		// Core sizes the scroll catcher to the space the column has, clamped
		// against the incinerator, the collapse button and the storage popup, and
		// puts it at the top of the column. Reading it back gives both without
		// redoing core's arithmetic.
		availableHeight = scroll.getOriginalHeight();
		clampScroll();

		int y = scroll.getOriginalY() + MARGIN;
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
				if (piece.mirrorOf != null && piece.mirrorOf == dragged)
				{
					continue;
				}

				int top = y + piece.dy;
				piece.widget.setOriginalY(top);
				piece.widget.setHidden(false);
				piece.widget.revalidate();
				expectedY.put(piece.widget, top);
			}

			y += row.height + MARGIN;
			used += row.height + MARGIN;
		}
	}

	/**
	 * Hiding the widget being dragged makes it vanish mid-drag when the column
	 * is laid out again because of a scroll.
	 */
	private void hide(Widget widget, @Nullable Widget dragged)
	{
		expectedY.put(widget, HIDDEN);
		if (widget != dragged)
		{
			widget.setHidden(true);
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
		if (parent == null || delta == 0)
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
	// Hit testing
	// ------------------------------------------------------------------

	boolean isDrawn()
	{
		return parent != null && !rendered.isEmpty();
	}

	/** The row a widget stands for, or null when it is not one of the column's rows. */
	@Nullable
	StripRow rowFor(@Nullable Widget widget)
	{
		return widget == null ? null : rowByWidget.get(widget);
	}

	/** Widgets we added, as opposed to core's. */
	boolean isOurs(@Nullable Widget widget)
	{
		return widget != null && ours.contains(widget);
	}

	/** -1 for the up arrow, 1 for the down arrow, 0 for anything else. */
	int arrowDirection(@Nullable Widget widget)
	{
		if (widget == null)
		{
			return 0;
		}
		return widget == up ? -1 : widget == down ? 1 : 0;
	}

	boolean isNewTab(@Nullable Widget widget)
	{
		return widget != null && widget == newTab;
	}

	/** Whether a point on the canvas falls inside the column. */
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
		if (point == null || parent == null)
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
