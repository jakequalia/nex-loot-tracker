package com.nexloottracker;

import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class NexKillContributionTrackerTest
{
	@Mock
	private Client client;

	@Mock
	private Player localPlayer;

	@Test
	public void getContributionPercentUsesLocalOverTotalDamage()
	{
		NexKillContributionTracker tracker = new NexKillContributionTracker();
		tracker.onHitsplatApplied(hitsplat(NpcID.NEX, 80, true, false), client);
		tracker.onHitsplatApplied(hitsplat(NpcID.NEX, 20, false, true), client);

		assertEquals(80.0, tracker.getContributionPercent(), 0.001);
	}

	@Test
	public void tracksNexMinionAndBloodReaverDamage()
	{
		NexKillContributionTracker tracker = new NexKillContributionTracker();
		List<Integer> contributionNpcIds = Arrays.asList(
			NpcID.NEX,
			NpcID.NEX_SPAWNING,
			NpcID.NEX_SOULSPLIT,
			NpcID.NEX_DEFLECT,
			NpcID.NEX_DYING,
			NpcID.NEX_SMOKEMAGE,
			NpcID.NEX_SHADOWMAGE,
			NpcID.NEX_BLOODMAGE,
			NpcID.NEX_ICEMAGE,
			NpcID.NEX_PRISON_BLOOD_REAVER,
			NpcID.NEX_PRISON_BLOOD_REAVER_BOSS
		);

		for (int npcId : contributionNpcIds)
		{
			tracker.onHitsplatApplied(hitsplat(npcId, 10, true, false), client);
		}

		assertEquals(contributionNpcIds.size() * 10, tracker.getLocalDamage());
		assertEquals(contributionNpcIds.size() * 10, tracker.getTotalDamage());
	}

	@Test
	public void ignoresNpcHitsOutsideNexEncounter()
	{
		NexKillContributionTracker tracker = new NexKillContributionTracker();
		tracker.onHitsplatApplied(hitsplat(NpcID.CORP_BEAST, 100, true, false), client);

		assertNull(tracker.getContributionPercent());
	}

	@Test
	public void ignoresHealingHitsplats()
	{
		NexKillContributionTracker tracker = new NexKillContributionTracker();
		tracker.onHitsplatApplied(hitsplat(NpcID.NEX, 40, true, false, HitsplatID.HEAL), client);

		assertNull(tracker.getContributionPercent());
	}

	@Test
	public void resetClearsTrackedDamage()
	{
		NexKillContributionTracker tracker = new NexKillContributionTracker();
		tracker.onHitsplatApplied(hitsplat(NpcID.NEX, 50, true, false), client);
		tracker.reset();

		assertNull(tracker.getContributionPercent());
	}

	private HitsplatApplied hitsplat(int npcId, int amount, boolean mine, boolean others)
	{
		return hitsplat(npcId, amount, mine, others, 0);
	}

	private HitsplatApplied hitsplat(int npcId, int amount, boolean mine, boolean others, int hitsplatType)
	{
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(npcId);

		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getAmount()).thenReturn(amount);
		when(hitsplat.getHitsplatType()).thenReturn(hitsplatType);
		when(hitsplat.isMine()).thenReturn(mine);
		when(hitsplat.isOthers()).thenReturn(others);

		HitsplatApplied event = mock(HitsplatApplied.class);
		when(event.getActor()).thenReturn(npc);
		when(event.getHitsplat()).thenReturn(hitsplat);
		when(client.getLocalPlayer()).thenReturn(localPlayer);

		return event;
	}
}
