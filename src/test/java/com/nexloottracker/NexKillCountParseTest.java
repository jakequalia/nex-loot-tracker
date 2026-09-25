package com.nexloottracker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NexKillCountParseTest
{
	@Test
	public void parsesKillCountsBelowOneThousand()
	{
		assertEquals(999, NexLootTrackerPlugin.parseKillCount("999"));
	}

	@Test
	public void parsesCommaFormattedKillCounts()
	{
		assertEquals(1000, NexLootTrackerPlugin.parseKillCount("1,000"));
		assertEquals(12345, NexLootTrackerPlugin.parseKillCount("12,345"));
	}
}
