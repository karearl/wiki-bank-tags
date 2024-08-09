package com.wikibanktags;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("wikibanktags")
public interface WikiBankTagsConfig extends Config
{
	@ConfigItem(
			keyName = "command",
			name = "Command",
			description = "The command to create a bank tag from a wiki category"
	)
	default String command()
	{
		return "bt";
	}
}