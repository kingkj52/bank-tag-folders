package com.banktagfolders;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a dev client with the plugin loaded: {@code gradlew run}.
 */
public class BankTagFoldersPluginTest
{
	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BankTagFoldersPlugin.class);
		RuneLite.main(args);
	}
}
