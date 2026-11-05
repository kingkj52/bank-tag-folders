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
import net.runelite.client.util.Text;

/**
 * What this plugin needs to ask of the core Bank Tags plugin.
 * <p>
 * Bank Tags draws the tag column, owns the tags, icons and layouts, and does
 * everything a tab's menu offers. This plugin only rearranges the column once
 * it has been drawn, so the list of things it asks for is short: which tabs
 * exist, which one is open, and how to open or close one.
 * <p>
 * Nothing here is injectable the ordinary way. {@code BankTagsService} is an
 * interface with no binding a side-loaded plugin's child injector can see, so it
 * is found among the loaded plugins, and every accessor degrades rather than
 * throwing.
 */
@Slf4j
@Singleton
public class CoreBankTags
{
	private final PluginManager pluginManager;
	private final ConfigManager configManager;
	private final BankTagFoldersConfig config;

	@Nullable
	private BankTagsService service;

	@Nullable
	private BankSearch bankSearch;

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
	private BankTagsService service()
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
	 * Resolve one of the core plugin's collaborators from the Bank Tags plugin's
	 * own injector, which is where they are bound. The root injector has no
	 * binding for the ones that depend on the plugin itself.
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
	private BankSearch bankSearch()
	{
		if (bankSearch == null)
		{
			bankSearch = resolve(BankSearch.class);
		}
		return bankSearch;
	}

	boolean isAvailable()
	{
		return service() != null;
	}

	/**
	 * Whether the core plugin is drawing its tag column. Folders have nothing to
	 * rearrange without it, so this plugin waits until it is on.
	 */
	boolean isCoreStripEnabled()
	{
		BankTagsConfig bankTagsConfig = configManager.getConfig(BankTagsConfig.class);
		// The core default is on, so an unreadable proxy counts as on too.
		return bankTagsConfig == null || bankTagsConfig.tabs();
	}

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

	@Nullable
	String activeTag()
	{
		BankTagsService bankTags = service();
		return bankTags == null ? null : bankTags.getActiveTag();
	}

	/**
	 * Open a tag the way the core column does, but with the option to leave
	 * layout spacing alone. Only used when that option is on: every other tab
	 * click is left to the core plugin.
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
			// dropped into the first free slot, from the very top, and the layout
			// is saved. Blank slots left as deliberate spacing get eaten. This
			// appends such items after the last one instead.
			options |= BankTagsService.OPTION_ITEMS_NOT_IN_LAYOUT_AT_BOTTOM;
		}

		bankTags.openBankTag(tag, options);
	}

	void closeTag()
	{
		BankTagsService bankTags = service();
		if (bankTags != null)
		{
			bankTags.closeBankTag();
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
