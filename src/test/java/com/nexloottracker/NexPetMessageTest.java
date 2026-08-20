package com.nexloottracker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NexPetMessageTest
{
	@Test
	public void recognizesFirstPetMessages()
	{
		assertTrue(NexLootTrackerPlugin.isFirstPetMessage(
			"You have a funny feeling like you're being followed."
		));
		assertTrue(NexLootTrackerPlugin.isFirstPetMessage(
			"You feel something weird sneaking into your backpack."
		));
	}

	@Test
	public void recognizesDuplicatePetMessage()
	{
		assertTrue(NexLootTrackerPlugin.isDuplicatePetMessage(
			"You have a funny feeling like you would have been followed..."
		));
	}

	@Test
	public void recognizesUntradeableNexlingMessage()
	{
		assertTrue(NexLootTrackerPlugin.isUntradeableNexlingMessage("Untradeable drop: Nexling"));
		assertTrue(NexLootTrackerPlugin.isUntradeableNexlingMessage("Untradeable drop: Nexling."));
	}

	@Test
	public void rejectsUnrelatedPetMessages()
	{
		assertFalse(NexLootTrackerPlugin.isUntradeableNexlingMessage("Untradeable drop: Pet chaos elemental"));
		assertFalse(NexLootTrackerPlugin.isFirstPetMessage("Your pet is not interested in that."));
		assertFalse(NexLootTrackerPlugin.isDuplicatePetMessage("You have a funny feeling like you're being followed."));
	}
}
