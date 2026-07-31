package com.thecardexchange.tcg;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev-mode launcher: side-loads this plugin into a real RuneLite client so it can
 * be run from the IDE / {@code ./gradlew run}. Not shipped.
 */
public class TheCardExchangeTcgPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TheCardExchangeTcgPlugin.class);
		RuneLite.main(args);
	}
}
