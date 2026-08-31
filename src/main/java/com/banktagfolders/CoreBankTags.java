package com.banktagfolders;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.bank.BankSearch;
import net.runelite.client.plugins.banktags.BankTagsConfig;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.plugins.banktags.tabs.TagTab;
import net.runelite.client.util.Text;

/**
 * Everything this plugin needs from the core Bank Tags plugin, in one place.
 * <p>
 * The core plugin stays the single source of truth for which tags exist, which
 * items carry them, what icon each one uses and what layout it has. This class
 * reads that state and asks the core plugin to act on it; it never keeps a
 * second copy.
 * <p>
 * Config is the durable store for the tab list, so config is what we read.
 * The core plugin's {@code TabManager} is an in-memory mirror filled by
 * {@code TabInterface.init()}, which never runs while the core strip is off --
 * so it cannot be read from, but it does have to be written to, because other
 * plugins consult it. See {@link #syncTabManager()}.
 * <p>
 * Nothing here is injectable the ordinary way either. {@code BankTagsService}
 * is an interface with no binding a side-loaded plugin's child injector can
 * see, and the managers are client-classloader singletons, so all of them are
 * resolved at runtime and every accessor degrades rather than throwing.
 */
@Slf4j
@Singleton
public class CoreBankTags
{
	/** The core plugin's name for its "show me every tab" pseudo-tag. */
	static final String TAGTABS = "tagtabs";

	private final PluginManager pluginManager;
	private final ConfigManager configManager;
	private final BankTagFoldersConfig config;

	@Nullable
	private BankTagsService service;

	@Nullable
	private TagManager tagManager;

	@Nullable
	private LayoutManager layoutManager;

	@Nullable
	private BankSearch bankSearch;

	@Nullable
	private TabManager tabManager;

	@Inject
	CoreBankTags(PluginManager pluginManager, ConfigManager configManager, BankTagFoldersConfig config)
	{
		this.pluginManager = pluginManager;
		this.configManager = configManager;
		this.config = config;
	}

	/**
	 * The core plugin, found among the loaded plugins because it implements the
	 * service interface itself.
	 */
	@Nullable
	BankTagsService service()
	{
		if (service == null)
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (plugin instanceof BankTagsService)
				{
					service = (BankTagsService) plugin;
					break;
				}
			}
		}
		return service;
	}

	/**
	 * Resolve one of the core plugin's collaborators.
	 * <p>
	 * Asked of the <em>Bank Tags plugin's own injector</em>, not the root one.
	 * Every plugin gets a child injector, and the core plugin's collaborators
	 * are just-in-time bound there -- {@code LayoutManager} necessarily so,
	 * since it depends on {@code BankTagsPlugin} itself, which the root injector
	 * has no binding for. Asking the root throws outright:
	 * <p>
	 * {@code Unable to create binding for LayoutManager. It was already
	 * configured on one or more child injectors}
	 * <p>
	 * The child also delegates to the root for anything it does not own, so this
	 * resolves everything the root could <em>and</em> guarantees we share the
	 * core plugin's own instances rather than quietly constructing a second set
	 * of singletons alongside them.
	 */
	@Nullable
	private <T> T resolve(Class<T> type)
	{
		BankTagsService bankTags = service();
		if (bankTags instanceof Plugin)
		{
			try
			{
				return ((Plugin) bankTags).getInjector().getInstance(type);
			}
			catch (Exception e)
			{
				log.debug("Could not resolve {} from the Bank Tags injector", type.getSimpleName(), e);
			}
		}

		try
		{
			return RuneLite.getInjector().getInstance(type);
		}
		catch (Exception e)
		{
			log.warn("Could not resolve {}", type.getSimpleName(), e);
			return null;
		}
	}

	@Nullable
	TagManager tagManager()
	{
		if (tagManager == null)
		{
			tagManager = resolve(TagManager.class);
		}
		return tagManager;
	}

	@Nullable
	LayoutManager layoutManager()
	{
		if (layoutManager == null)
		{
			layoutManager = resolve(LayoutManager.class);
		}
		return layoutManager;
	}

	@Nullable
	BankSearch bankSearch()
	{
		if (bankSearch == null)
		{
			bankSearch = resolve(BankSearch.class);
		}
		return bankSearch;
	}

	@Nullable
	TabManager tabManager()
	{
		if (tabManager == null)
		{
			tabManager = resolve(TabManager.class);
		}
		return tabManager;
	}

	boolean isAvailable()
	{
		return service() != null;
	}

	/**
	 * Whether the core plugin is still drawing its own tag column.
	 * <p>
	 * We stay dormant while it is: both strips build their widgets as dynamic
	 * children of the same bank container, and would delete each other's on
	 * every rebuild.
	 */
	boolean isCoreStripEnabled()
	{
		BankTagsConfig config = configManager.getConfig(BankTagsConfig.class);
		// The core default is on, so an unreadable proxy has to count as "on"
		// too -- drawing over a live strip is the worse failure.
		return config == null || config.tabs();
	}

	/**
	 * Whether the core plugin is set to reopen the last tag with the bank.
	 * <p>
	 * Honoured rather than duplicated: it is the same feature, it has always
	 * lived in Bank Tags, and a second setting of the same name in a second
	 * plugin would be a coin toss as to which one a player reaches for.
	 */
	boolean remembersLastTab()
	{
		BankTagsConfig config = configManager.getConfig(BankTagsConfig.class);
		return config == null || config.rememberTab();
	}

	/**
	 * The tag the bank was last showing.
	 * <p>
	 * Already written for us: the core plugin records it whenever a tag is
	 * opened or closed, and that happens through {@code BankTagsPlugin.openTag}
	 * regardless of whether its own strip is drawing. Closing the bank passes a
	 * null tag, which returns before touching the setting -- so the value
	 * survives, and only the restoring half was missing.
	 */
	@Nullable
	String lastTab()
	{
		BankTagsConfig config = configManager.getConfig(BankTagsConfig.class);
		if (config == null)
		{
			return null;
		}
		String tab = config.tab();
		return tab == null || tab.isEmpty() ? null : tab;
	}

	// ------------------------------------------------------------------
	// Tab list
	// ------------------------------------------------------------------

	/** Tag names, in the order the core plugin stores them. */
	List<String> tagTabs()
	{
		String csv = configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG);
		if (csv == null || csv.isEmpty())
		{
			return new ArrayList<>();
		}
		return new ArrayList<>(Text.fromCSV(csv));
	}

	void setTagTabs(List<String> tags)
	{
		configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tags));
	}

	boolean hasTab(String tag)
	{
		return tagTabs().contains(Text.standardize(tag));
	}

	/**
	 * Mirror the tab list into the core plugin's {@code TabManager}.
	 * <p>
	 * {@code TabManager} is normally filled by {@code TabInterface.init()},
	 * which only runs while the core strip is drawing. With the strip off it
	 * stays empty -- and it is not private bookkeeping: other plugins read it as
	 * the answer to "does this tag tab exist?".
	 * <p>
	 * Bank Tag Layouts is the one that matters here. Its
	 * {@code isVanillaLayoutEnabled()} does {@code tabManager.find(tag) != null
	 * && hasRuneliteLayout(tag)} to decide whether the core layout engine owns a
	 * tab. Against an empty TabManager that is always false, so it concludes the
	 * core engine is not involved and lays the bank out itself -- on top of the
	 * core layout manager, which is still laying out the same tab. Two engines,
	 * one bank, and the duplicate entries lose.
	 * <p>
	 * Taking over the strip means taking over this too.
	 */
	void syncTabManager()
	{
		TabManager tabs = tabManager();
		if (tabs == null)
		{
			return;
		}

		try
		{
			List<String> live = tagTabs();
			List<TagTab> current = tabs.getTabs();

			// Cheap identity check first: this runs on every bank build and the
			// list almost never changes.
			if (current.size() == live.size())
			{
				boolean same = true;
				for (int i = 0; i < live.size(); i++)
				{
					if (!live.get(i).equals(current.get(i).getTag()))
					{
						same = false;
						break;
					}
				}
				if (same)
				{
					return;
				}
			}

			// Rebuilt in the config's own order, so a TabManager.save() by
			// anything else writes back exactly what is already stored.
			current.clear();
			for (String tag : live)
			{
				TagTab tab = new TagTab();
				tab.setTag(tag);
				tab.setIconItemId(iconFor(tag));
				current.add(tab);
			}
		}
		catch (Exception e)
		{
			log.warn("Could not mirror the tab list into the core TabManager", e);
		}
	}


	/** Create a tab if it does not already exist. Returns false when taken. */
	boolean createTab(String tag)
	{
		String name = Text.standardize(tag);
		List<String> tabs = tagTabs();
		if (name.isEmpty() || tabs.contains(name))
		{
			return false;
		}
		tabs.add(name);
		setTagTabs(tabs);
		return true;
	}

	/**
	 * Delete the tab, its icon and its layout, leaving the tag on items.
	 * <p>
	 * The core plugin offers this separately from removing the tag itself, and
	 * the distinction matters: one is cosmetic, the other is destructive.
	 */
	void removeTabOnly(String tag)
	{
		List<String> tabs = tagTabs();
		if (tabs.remove(tag))
		{
			setTagTabs(tabs);
		}

		unsetIcon(tag);

		LayoutManager layouts = layoutManager();
		if (layouts != null)
		{
			layouts.removeLayout(tag);
		}
	}

	/** Strip a tag from every item carrying it. */
	void removeTagFromItems(String tag)
	{
		TagManager tags = tagManager();
		if (tags != null)
		{
			tags.removeTag(tag);
		}
	}

	// ------------------------------------------------------------------
	// Icons
	// ------------------------------------------------------------------

	/** Item id drawn on a tag's tab, or 0 when it has never been given one. */
	int iconFor(String tag)
	{
		String value = configManager.getConfiguration(
			BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + Text.standardize(tag));
		if (value == null)
		{
			return 0;
		}
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	void setIcon(String tag, int itemId)
	{
		configManager.setConfiguration(
			BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + Text.standardize(tag), itemId);
	}

	void unsetIcon(String tag)
	{
		configManager.unsetConfiguration(
			BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + Text.standardize(tag));
	}

	// ------------------------------------------------------------------
	// Layouts
	// ------------------------------------------------------------------

	/**
	 * Whether a tag has a layout, without parsing it.
	 * <p>
	 * The column asks this once per row on every bank rebuild, purely to label a
	 * menu option "Enable" or "Disable layout". {@code loadLayout} would read the
	 * config and parse the whole stored layout -- several hundred integers for a
	 * large tab -- to answer a yes/no question, and the bank rebuilds on every
	 * keystroke in its search box.
	 */
	boolean hasLayout(String tag)
	{
		return configManager.getConfiguration(
			BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_LAYOUT_PREFIX + Text.standardize(tag)) != null;
	}

	@Nullable
	Layout loadLayout(String tag)
	{
		LayoutManager layouts = layoutManager();
		return layouts == null ? null : layouts.loadLayout(tag);
	}

	void saveLayout(Layout layout)
	{
		LayoutManager layouts = layoutManager();
		if (layouts != null)
		{
			layouts.saveLayout(layout);
		}
	}

	void removeLayout(String tag)
	{
		LayoutManager layouts = layoutManager();
		if (layouts != null)
		{
			layouts.removeLayout(tag);
		}
	}

	// ------------------------------------------------------------------
	// Item tags
	// ------------------------------------------------------------------

	/** @param variation tag every variant of the item, as shift-drag does */
	void tagItem(int itemId, String tag, boolean variation)
	{
		TagManager tags = tagManager();
		if (tags != null)
		{
			tags.addTag(itemId, tag, variation);
		}
	}

	List<Integer> itemsForTag(String tag)
	{
		TagManager tags = tagManager();
		return tags == null ? new ArrayList<>() : tags.getItemsForTag(tag);
	}

	void renameTagOnItems(String from, String to)
	{
		TagManager tags = tagManager();
		if (tags != null)
		{
			tags.renameTag(from, to);
		}
	}

	// ------------------------------------------------------------------
	// Opening tags
	// ------------------------------------------------------------------

	@Nullable
	String activeTag()
	{
		BankTagsService bankTags = service();
		return bankTags == null ? null : bankTags.getActiveTag();
	}

	/**
	 * Open a tag, layout and all.
	 * <p>
	 * {@code openBankTag} loads the tag's layout itself unless told not to, and
	 * routes through the core plugin's own {@code openTag} -- which also sets
	 * the state its Remove-tag, Duplicate-item and Tag-inventory menu entries
	 * read. Those keep working with the core strip switched off precisely
	 * because we go through here rather than filtering the bank ourselves.
	 */
	void openTag(String tag)
	{
		BankTagsService bankTags = service();
		if (bankTags == null)
		{
			return;
		}

		int options = BankTagsService.OPTION_ALLOW_MODIFICATIONS;
		if (config.protectLayoutSpacing())
		{
			// Without this, a tagged item that is not in the tab's layout yet is
			// dropped into the first free slot -- starting from the very top --
			// and the layout is then saved. Any blank slots left as deliberate
			// spacing get eaten permanently. This option appends such items
			// after the last one instead, so an arranged tab stays arranged.
			options |= BankTagsService.OPTION_ITEMS_NOT_IN_LAYOUT_AT_BOTTOM;
		}

		bankTags.openBankTag(tag, options);
	}

	/** Open the core plugin's "every tab as an icon" pseudo-tag. */
	void openTagTabOverview()
	{
		BankTagsService bankTags = service();
		if (bankTags != null)
		{
			bankTags.openBankTag(TAGTABS, 0);
		}
	}

	void closeTag()
	{
		BankTagsService bankTags = service();
		if (bankTags != null)
		{
			bankTags.closeBankTag();
		}
	}

	/** Re-apply the active tag's filter after its contents changed. */
	void reloadActiveTab()
	{
		String tag = activeTag();
		if (tag != null)
		{
			openTag(tag);
		}
	}

	void relayoutBank()
	{
		BankSearch search = bankSearch();
		if (search != null)
		{
			search.layoutBank();
		}
	}

	void resetBank()
	{
		BankSearch search = bankSearch();
		if (search != null)
		{
			search.reset(true);
		}
	}
}
