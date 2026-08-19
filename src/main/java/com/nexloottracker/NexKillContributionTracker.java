package com.nexloottracker;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.gameval.NpcID;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks local and total contribution damage during the current Nex kill.
 * Mirrors DPS Counter boss damage accounting without reading that plugin directly.
 */
public class NexKillContributionTracker
{
	private static final Set<Integer> CONTRIBUTION_NPC_IDS = new HashSet<>(Arrays.asList(
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
	));

	private int localDamage;
	private int totalDamage;

	public void reset()
	{
		localDamage = 0;
		totalDamage = 0;
	}

	public void onHitsplatApplied(HitsplatApplied event, Client client)
	{
		final Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		if (!CONTRIBUTION_NPC_IDS.contains(((NPC) actor).getId()))
		{
			return;
		}

		final Hitsplat hitsplat = event.getHitsplat();
		if (hitsplat == null || hitsplat.getHitsplatType() == HitsplatID.HEAL)
		{
			return;
		}

		final Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		final int amount = hitsplat.getAmount();
		if (amount <= 0)
		{
			return;
		}

		if (hitsplat.isMine())
		{
			localDamage += amount;
			totalDamage += amount;
		}
		else if (hitsplat.isOthers())
		{
			totalDamage += amount;
		}
	}

	public Double getContributionPercent()
	{
		if (totalDamage <= 0)
		{
			return null;
		}

		final double percent = (localDamage * 100.0) / totalDamage;
		if (Double.isNaN(percent) || Double.isInfinite(percent))
		{
			return null;
		}

		return Math.max(0.0, Math.min(100.0, percent));
	}

	int getLocalDamage()
	{
		return localDamage;
	}

	int getTotalDamage()
	{
		return totalDamage;
	}
}
