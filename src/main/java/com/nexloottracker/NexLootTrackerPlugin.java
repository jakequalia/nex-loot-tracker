package com.nexloottracker;

import com.google.inject.Provides;
import com.nexloottracker.filereadwriter.FileReadWriter;
import com.nexloottracker.ui.NexLootTrackerPanel;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.MessageNode;
import net.runelite.api.NPC;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.Player;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.events.ChatInput;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "Nex Loot Tracker"
)
public class NexLootTrackerPlugin extends Plugin
{
	private static final String NEX_DRY_COMMAND = "!nexdry";
	private static final String NEX_DRY_STREAK_COMMAND = "!nexdrystreak";
	private static final String NEX_LAST_COMMAND = "!nexlast";
	private static final String NEX_LAST_ITEM_COMMAND = "!nexlastitem";
	private static final Color NEX_DRY_COLOR = new Color(128, 0, 128);

	private static final int NEX_REGION_ID = 11603;
	private static final Set<Integer> NEX_REGION_IDS = new HashSet<>(Arrays.asList(
		11601,
		11602,
		NEX_REGION_ID,
		48385 // instanced Nex world view (observed in live client)
	));
	private static final int FINALIZE_DELAY_TICKS = 8;

	private static final Set<Integer> NEX_NPC_IDS = new HashSet<>(Arrays.asList(
		NpcID.NEX,
		NpcID.NEX_SPAWNING,
		NpcID.NEX_SOULSPLIT,
		NpcID.NEX_DEFLECT,
		NpcID.NEX_DYING
	));

	private static final Set<Integer> FINAL_NEX_DESPAWN_IDS = new HashSet<>(Arrays.asList(
		NpcID.NEX,
		NpcID.NEX_DYING
	));

	private static final Pattern KILL_COUNT_PATTERN = Pattern.compile("Your Nex kill count is:?\\s*(\\d+)\\.");
	private static final Pattern FIGHT_DURATION_PATTERN = Pattern.compile(
		"Fight duration:\\s*(\\d+(?::\\d{2}){1,2})",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern UNIQUE_DROP_PATTERN = Pattern.compile("(.+?) (?:has )?received a drop: (.+)");
	private static final Pattern MVP_SELF_PATTERN = Pattern.compile("^You were the MVP for this fight!\\.?$");
	private static final Pattern MVP_NAMED_PATTERN = Pattern.compile("^The MVP for this fight was:?\\s*(.+?)\\.?$");
	private static final Pattern MVP_LEGACY_PATTERN = Pattern.compile("^(.+?) dealt the most damage to Nex\\.?$");
	private static final Pattern PET_PATTERN = Pattern.compile("(.+?) (?:has )?received a drop: Nexling");
	private static final Pattern FIRST_PET_PATTERN = Pattern.compile(
		"^(?:You have a funny feeling like you're being followed|"
			+ "You feel something weird sneaking into your backpack)\\.?$",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern DUPLICATE_PET_PATTERN = Pattern.compile(
		"^You have a funny feeling like you would have been followed\\.\\.\\.$",
		Pattern.CASE_INSENSITIVE
	);
	private static final Pattern UNTRADEABLE_NEXLING_PATTERN = Pattern.compile(
		"^Untradeable drop:\\s*Nexling\\.?$",
		Pattern.CASE_INSENSITIVE
	);

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private NexLootTrackerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private FileReadWriter fileReadWriter;

	@Inject
	private PartyService partyService;

	@Inject
	private ChatCommandManager chatCommandManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ConfigManager configManager;

	@Inject
	private NexChatClient nexChatClient;

	private final NexKillContributionTracker contributionTracker = new NexKillContributionTracker();

	private NexLootTrackerPanel panel;
	private NavigationButton navButton;

	private NexLootTracker currentKill;
	private final List<NexLootTracker> pendingUniqueKills = new ArrayList<>();
	private int finalizeDelayTicks = -1;
	private boolean isFirstGameTick = true;

	@Provides
	NexLootTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NexLootTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new NexLootTrackerPanel(itemManager, fileReadWriter, config, configManager, client);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel-icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Nex Loot Tracker")
			.priority(6)
			.icon(icon)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		registerChatCommands();

		refreshKillListFromDisk();
	}

	@Override
	protected void shutDown()
	{
		unregisterChatCommands();
		clientToolbar.removeNavigation(navButton);
		resetCurrentKill();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!NexLootTrackerConfig.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		SwingUtilities.invokeLater(panel::loadKillList);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (WorldUtils.playerOnBetaWorld(client))
		{
			return;
		}

		if (event.getGameState() == GameState.LOGGING_IN || event.getGameState() == GameState.LOGGED_IN)
		{
			refreshKillListFromDisk();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			isFirstGameTick = true;
		}
		else if (event.getGameState() == GameState.HOPPING)
		{
			isFirstGameTick = true;
			resetCurrentKill();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (client.getLocalPlayer() == null)
		{
			return;
		}

		if (isFirstGameTick)
		{
			isFirstGameTick = false;
			refreshKillListFromDisk();
		}

		if (finalizeDelayTicks >= 0)
		{
			finalizeDelayTicks--;

			if (finalizeDelayTicks == 0)
			{
				finalizeCurrentKill();
			}
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (WorldUtils.playerOnBetaWorld(client))
		{
			return;
		}

		contributionTracker.onHitsplatApplied(event, client);
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (WorldUtils.playerOnBetaWorld(client))
		{
			log.info("Nex loot ignored: player is on a beta world");
			return;
		}

		final NPC npc = event.getNpc();
		if (npc == null || !NEX_NPC_IDS.contains(npc.getId()))
		{
			if (npc != null)
			{
				log.debug("NpcLootReceived ignored: npcId={} (not Nex)", npc.getId());
			}
			return;
		}

		final NexLootTracker kill = getOrCreateCurrentKill();
		kill.setLoggedIn(true);
		kill.setAccountHash(client.getAccountHash());
		kill.setProfileType(String.valueOf(RuneScapeProfileType.getCurrent(client)));
		kill.setTeamSize(getTeamSize());
		kill.setLootList(buildLootList(event.getItems()));
		kill.setDate(System.currentTimeMillis());

		for (NexLootTrackerItem item : kill.getLootList())
		{
			if (!NexUniques.isUniqueItemId(item.getId()))
			{
				continue;
			}

			final NexUniques unique = NexUniques.fromItemId(item.getId());
			if (unique == NexUniques.NEXLING)
			{
				handlePetDrop(getLocalPlayerName(), getLocalPlayerName());
			}
			else
			{
				applyUniqueDrop(unique, getLocalPlayerName(), true, item.getPrice());
			}
			break;
		}

		log.info("Recorded personal Nex loot: {} items, teamSize={}, npcId={}",
			kill.getLootList().size(), kill.getTeamSize(), npc.getId());
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (WorldUtils.playerOnBetaWorld(client))
		{
			return;
		}

		if (!isRelevantChatType(event.getType()))
		{
			return;
		}

		final String message = Text.removeTags(event.getMessage()).replace('\u00A0', ' ');

		if (!shouldProcessNexChat(message))
		{
			if (message.contains("Nex") || message.contains("MVP") || message.contains("kill count"))
			{
				log.info("Nex-related chat ignored (not in Nex area, region={}): {}",
					client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation().getRegionID() : -1,
					message);
			}
			return;
		}

		log.debug("Nex area chat [{}]: {}", event.getType(), message);
		final String playerName = getLocalPlayerName();

		Matcher matcher = KILL_COUNT_PATTERN.matcher(message);
		if (matcher.find())
		{
			final NexLootTracker kill = getOrCreateCurrentKill();
			kill.setLoggedIn(true);
			kill.setKillComplete(true);
			kill.setCompletionCount(Integer.parseInt(matcher.group(1)));
			kill.setTeamSize(getTeamSize());
			kill.setAccountHash(client.getAccountHash());
			kill.setProfileType(String.valueOf(RuneScapeProfileType.getCurrent(client)));
			kill.setDate(System.currentTimeMillis());
			log.info("Nex kill count detected: kc={}, teamSize={}, scheduling finalize in {} ticks",
				kill.getCompletionCount(), kill.getTeamSize(), FINALIZE_DELAY_TICKS);
			snapshotKillContribution(kill);
			scheduleFinalize();
			return;
		}

		matcher = FIGHT_DURATION_PATTERN.matcher(message);
		if (matcher.find())
		{
			final Long durationMs = parseFightDurationMs(matcher.group(1));
			if (durationMs != null)
			{
				final NexLootTracker kill = getOrCreateCurrentKill();
				kill.setKillDurationMs(durationMs);
				log.info("Nex fight duration from chat: {} -> {} ms", matcher.group(1), durationMs);
			}
			return;
		}

		if (handleMvpMessage(message, playerName))
		{
			return;
		}

		if (isOwnNexlingMessage(message))
		{
			handlePetDrop(playerName, playerName);
			return;
		}

		// Prefer pet pattern first: UNIQUE_DROP_PATTERN also matches "received a drop: Nexling".
		matcher = PET_PATTERN.matcher(message);
		if (matcher.find())
		{
			handlePetDrop(matcher.group(1).trim(), playerName);
			return;
		}

		matcher = UNIQUE_DROP_PATTERN.matcher(message);
		if (matcher.find())
		{
			handleUniqueDrop(matcher.group(1).trim(), matcher.group(2).trim(), playerName);
		}
	}

	private boolean handleMvpMessage(String message, String playerName)
	{
		if (MVP_SELF_PATTERN.matcher(message).matches())
		{
			setMvp(getOrCreateCurrentKill(), playerName, playerName);
			return true;
		}

		Matcher matcher = MVP_NAMED_PATTERN.matcher(message);
		if (matcher.matches())
		{
			final String mvp = Text.toJagexName(matcher.group(1).trim());
			setMvp(getOrCreateCurrentKill(), mvp, playerName);
			return true;
		}

		matcher = MVP_LEGACY_PATTERN.matcher(message);
		if (matcher.matches())
		{
			final String mvp = Text.toJagexName(matcher.group(1).trim());
			setMvp(getOrCreateCurrentKill(), mvp, playerName);
			return true;
		}

		return false;
	}

	private void setMvp(NexLootTracker kill, String mvpName, String localPlayerName)
	{
		kill.setMvp(mvpName);
		kill.setMvpInOwnName(mvpName.equalsIgnoreCase(localPlayerName));
	}

	private void handleUniqueDrop(String receiver, String itemName, String playerName)
	{
		final NexUniques unique = NexUniques.fromName(itemName);
		if (unique == null)
		{
			return;
		}

		// Nexling is tracked via petReceiver / petInMyName, not specialLoot.
		if (unique == NexUniques.NEXLING)
		{
			handlePetDrop(receiver, playerName);
			return;
		}

		final String jagexReceiver = normalizeReceiver(receiver);
		final boolean inOwnName = jagexReceiver.equalsIgnoreCase(playerName);
		applyUniqueDrop(unique, jagexReceiver, inOwnName, getItemPrice(unique.getItemId()));
	}

	private void applyUniqueDrop(NexUniques unique, String receiver, boolean inOwnName, int itemPrice)
	{
		if (unique == null)
		{
			return;
		}

		final NexLootTracker kill = getOrCreateCurrentKill();
		kill.setLoggedIn(true);
		kill.setAccountHash(client.getAccountHash());
		kill.setProfileType(String.valueOf(RuneScapeProfileType.getCurrent(client)));

		if (isRecordedUnique(unique.getName(), receiver))
		{
			return;
		}

		// Pet and unique-table drops share a killCountID but get separate log rows
		// so both appear in the purple list (same pattern as multiple uniques).
		if (kill.getSpecialLoot().isEmpty() && !kill.getPetReceiver().isEmpty())
		{
			pendingUniqueKills.add(createPendingPetEntry(
				kill,
				kill.getPetReceiver(),
				kill.isPetInMyName()
			));
			clearPet(kill);
		}

		if (kill.getSpecialLoot().isEmpty())
		{
			setSpecialLootOnKill(kill, unique, receiver, inOwnName, itemPrice);
			return;
		}

		// Prefer the local player's unique on the main kill record (holds personal loot list).
		if (inOwnName && !kill.isSpecialLootInOwnName())
		{
			pendingUniqueKills.add(createPendingUniqueFromCurrent(kill));
			clearSpecialLoot(kill);
			setSpecialLootOnKill(kill, unique, receiver, inOwnName, itemPrice);
			return;
		}

		final NexLootTracker pending = createPendingUniqueEntry(kill, unique, receiver, inOwnName, itemPrice);
		pendingUniqueKills.add(pending);
	}

	private NexLootTracker createPendingUniqueFromCurrent(NexLootTracker source)
	{
		final NexLootTracker pending = copyKill(source);
		pending.setUniqueID(UUID.randomUUID().toString());
		pending.setLootList(new ArrayList<>());
		clearPet(pending);
		return pending;
	}

	private NexLootTracker createPendingUniqueEntry(
		NexLootTracker source,
		NexUniques unique,
		String receiver,
		boolean inOwnName,
		int itemPrice
	)
	{
		final NexLootTracker pending = copyKill(source);
		pending.setUniqueID(UUID.randomUUID().toString());
		pending.setLootList(new ArrayList<>());
		clearSpecialLoot(pending);
		clearPet(pending);
		setSpecialLootOnKill(pending, unique, receiver, inOwnName, itemPrice);
		return pending;
	}

	private NexLootTracker createPendingPetEntry(NexLootTracker source, String receiver, boolean inOwnName)
	{
		final NexLootTracker pending = copyKill(source);
		pending.setUniqueID(UUID.randomUUID().toString());
		pending.setLootList(new ArrayList<>());
		clearSpecialLoot(pending);
		clearPet(pending);
		pending.setPetReceiver(receiver);
		pending.setPetInMyName(inOwnName);
		return pending;
	}

	private void setSpecialLootOnKill(
		NexLootTracker kill,
		NexUniques unique,
		String receiver,
		boolean inOwnName,
		int itemPrice
	)
	{
		kill.setSpecialLoot(unique.getName());
		kill.setSpecialLootReceiver(receiver);
		kill.setSpecialLootInOwnName(inOwnName);
		kill.setSpecialLootValue(itemPrice);
		setSplits(kill);
	}

	private void clearSpecialLoot(NexLootTracker kill)
	{
		kill.setSpecialLoot("");
		kill.setSpecialLootReceiver("");
		kill.setSpecialLootInOwnName(false);
		kill.setSpecialLootValue(-1);
		kill.setLootSplitReceived(-1);
		kill.setLootSplitPaid(-1);
		kill.setFreeForAll(false);
	}

	private void clearPet(NexLootTracker kill)
	{
		kill.setPetReceiver("");
		kill.setPetInMyName(false);
	}

	private boolean isRecordedUnique(String itemName, String receiver)
	{
		if (matchesUnique(currentKill, itemName, receiver))
		{
			return true;
		}

		for (NexLootTracker pending : pendingUniqueKills)
		{
			if (matchesUnique(pending, itemName, receiver))
			{
				return true;
			}
		}

		return false;
	}

	private boolean matchesUnique(NexLootTracker kill, String itemName, String receiver)
	{
		if (kill == null || kill.getSpecialLoot().isEmpty())
		{
			return false;
		}

		return kill.getSpecialLoot().equalsIgnoreCase(itemName)
			&& kill.getSpecialLootReceiver().equalsIgnoreCase(receiver);
	}

	private String normalizeReceiver(String receiver)
	{
		String normalized = receiver.trim().replaceAll("^\\[[^\\]]+\\]\\s*", "");
		return Text.toJagexName(normalized.trim());
	}

	private void syncKillMetadata(NexLootTracker target, NexLootTracker source)
	{
		target.setKillComplete(source.isKillComplete());
		target.setCompletionCount(source.getCompletionCount());
		target.setTeamSize(source.getTeamSize());
		target.setMvp(source.getMvp());
		target.setMvpInOwnName(source.isMvpInOwnName());
		target.setDate(source.getDate());
		target.setKillDurationMs(source.getKillDurationMs());
	}

	private void handlePetDrop(String receiver, String playerName)
	{
		final String jagexReceiver = normalizeReceiver(receiver);
		final boolean inOwnName = jagexReceiver.equalsIgnoreCase(playerName);

		if (currentKill == null)
		{
			currentKill = createNewKill();
		}

		if (isRecordedPet(jagexReceiver))
		{
			return;
		}

		// If a unique (or another pet) is already on this kill, fork onto a pending row
		// so both drops appear separately in the UI while sharing killCountID.
		if (!currentKill.getSpecialLoot().isEmpty() || !currentKill.getPetReceiver().isEmpty())
		{
			final NexLootTracker pending = createPendingPetEntry(currentKill, jagexReceiver, inOwnName);
			if (currentKill.isKillComplete())
			{
				fileReadWriter.writeToFile(pending);
				SwingUtilities.invokeLater(() -> panel.addKill(pending, false));
			}
			else
			{
				pendingUniqueKills.add(pending);
			}
			log.info("Nexling pet recorded on pending row: receiver={}, own={}", jagexReceiver, inOwnName);
			return;
		}

		currentKill.setPetReceiver(jagexReceiver);
		currentKill.setPetInMyName(inOwnName);
		log.info("Nexling pet recorded: receiver={}, own={}", jagexReceiver, inOwnName);
	}

	private boolean isRecordedPet(String receiver)
	{
		if (currentKill != null
			&& !currentKill.getPetReceiver().isEmpty()
			&& currentKill.getPetReceiver().equalsIgnoreCase(receiver))
		{
			return true;
		}

		for (NexLootTracker pending : pendingUniqueKills)
		{
			if (!pending.getPetReceiver().isEmpty()
				&& pending.getPetReceiver().equalsIgnoreCase(receiver))
			{
				return true;
			}
		}

		return false;
	}

	@Subscribe(priority = 1.0f)
	public void onNpcDespawned(NpcDespawned event)
	{
		if (WorldUtils.playerOnBetaWorld(client))
		{
			return;
		}

		final NPC npc = event.getNpc();
		if (npc == null || !npc.isDead() || !FINAL_NEX_DESPAWN_IDS.contains(npc.getId()))
		{
			return;
		}

		snapshotKillContribution(getOrCreateCurrentKill());
	}

	private void scheduleFinalize()
	{
		finalizeDelayTicks = FINALIZE_DELAY_TICKS;
	}

	private void finalizeCurrentKill()
	{
		if (currentKill == null)
		{
			log.warn("finalizeCurrentKill called but currentKill is null");
			return;
		}

		log.info("Finalizing Nex kill: kc={}, lootItems={}, specialLoot={}, teamSize={}",
			currentKill.getCompletionCount(),
			currentKill.getLootList().size(),
			currentKill.getSpecialLoot(),
			currentKill.getTeamSize());

		if (currentKill.getTeamSize() <= 0)
		{
			currentKill.setTeamSize(getTeamSize());
		}

		if (!currentKill.getSpecialLoot().isEmpty() && currentKill.getSpecialLootValue() <= 0)
		{
			final NexUniques unique = NexUniques.fromName(currentKill.getSpecialLoot());
			if (unique != null)
			{
				currentKill.setSpecialLootValue(getItemPrice(unique.getItemId()));
				setSplits(currentKill);
			}
		}

		currentKill.setKillComplete(true);
		snapshotKillContribution(currentKill);
		Double contribution = currentKill.getKillContribution();
		log.info("Nex kill contribution: {} (local={}, total={})",
			contribution,
			contributionTracker.getLocalDamage(),
			contributionTracker.getTotalDamage());
		log.info("Nex kill duration: {} ms", currentKill.getKillDurationMs());

		fileReadWriter.ensureProfile(client.getUsername(), client.getAccountHash());

		for (NexLootTracker pending : pendingUniqueKills)
		{
			syncKillMetadata(pending, currentKill);
			pending.setKillContribution(contribution);
			fileReadWriter.writeToFile(pending);
			final NexLootTracker writtenPending = pending;
			SwingUtilities.invokeLater(() -> panel.addKill(writtenPending, false));
		}

		fileReadWriter.writeToFile(currentKill);
		final NexLootTracker writtenKill = currentKill;
		SwingUtilities.invokeLater(() -> panel.addKill(writtenKill, true));
		resetCurrentKill();
	}

	private void snapshotKillContribution(NexLootTracker kill)
	{
		if (kill == null)
		{
			return;
		}

		final Double contribution = getKillContributionPercent();
		kill.setKillContribution(contribution);
		log.info("Snapshotted Nex kill contribution: {} (local={}, total={})",
			contribution,
			contributionTracker.getLocalDamage(),
			contributionTracker.getTotalDamage());
	}

	/**
	 * Parses chat durations like {@code 4:08} or {@code 1:04:08} into milliseconds.
	 */
	static Long parseFightDurationMs(String duration)
	{
		if (duration == null || duration.isEmpty())
		{
			return null;
		}

		final String[] parts = duration.split(":");
		if (parts.length < 2 || parts.length > 3)
		{
			return null;
		}

		try
		{
			long totalSeconds = 0;
			for (String part : parts)
			{
				totalSeconds = totalSeconds * 60 + Integer.parseInt(part);
			}
			return totalSeconds * 1000L;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private Double getKillContributionPercent()
	{
		final Double contribution = contributionTracker.getContributionPercent();
		if (contribution == null)
		{
			log.info("Kill contribution unavailable: no Nex encounter damage tracked for this kill");
		}

		return contribution;
	}

	private NexLootTracker getOrCreateCurrentKill()
	{
		if (currentKill == null)
		{
			currentKill = createNewKill();
		}

		return currentKill;
	}

	private NexLootTracker createNewKill()
	{
		final NexLootTracker kill = new NexLootTracker();
		kill.setLoggedIn(true);
		kill.setAccountHash(client.getAccountHash());
		kill.setProfileType(String.valueOf(RuneScapeProfileType.getCurrent(client)));
		kill.setTeamSize(getTeamSize());
		kill.setDate(System.currentTimeMillis());
		return kill;
	}

	private NexLootTracker copyKill(NexLootTracker source)
	{
		final NexLootTracker copy = new NexLootTracker();
		copy.setAccountHash(source.getAccountHash());
		copy.setProfileType(source.getProfileType());
		copy.setLoggedIn(source.isLoggedIn());
		copy.setKillComplete(source.isKillComplete());
		copy.setFreeForAll(source.isFreeForAll());
		copy.setTeamSize(source.getTeamSize());
		copy.setCompletionCount(source.getCompletionCount());
		copy.setMvp(source.getMvp());
		copy.setMvpInOwnName(source.isMvpInOwnName());
		copy.setPetReceiver(source.getPetReceiver());
		copy.setPetInMyName(source.isPetInMyName());
		copy.setLootList(new ArrayList<>(source.getLootList()));
		copy.setKillCountID(source.getKillCountID());
		copy.setDate(source.getDate());
		copy.setKillDurationMs(source.getKillDurationMs());
		return copy;
	}

	private void resetCurrentKill()
	{
		currentKill = null;
		pendingUniqueKills.clear();
		finalizeDelayTicks = -1;
		contributionTracker.reset();
	}

	private void setSplits(NexLootTracker kill)
	{
		if (kill.getSpecialLoot().isEmpty() || kill.getTeamSize() <= 0)
		{
			return;
		}

		final int lootSplit = kill.getSpecialLootValue() / kill.getTeamSize();
		final int cutoff = config.FFACutoff();

		if (config.defaultFFA() || lootSplit < cutoff)
		{
			kill.setFreeForAll(true);
			if (kill.isSpecialLootInOwnName())
			{
				kill.setLootSplitReceived(kill.getSpecialLootValue());
			}
		}
		else if (kill.isSpecialLootInOwnName())
		{
			kill.setLootSplitPaid(kill.getSpecialLootValue() - lootSplit);
			kill.setLootSplitReceived(lootSplit);
		}
		else
		{
			kill.setLootSplitReceived(lootSplit);
		}
	}

	private ArrayList<NexLootTrackerItem> buildLootList(java.util.Collection<ItemStack> items)
	{
		final ArrayList<NexLootTrackerItem> lootList = new ArrayList<>();

		for (ItemStack stack : items)
		{
			if (stack.getId() <= 0)
			{
				continue;
			}

			final ItemComposition comp = itemManager.getItemComposition(stack.getId());
			final NexLootTrackerItem item = new NexLootTrackerItem();
			item.setName(comp.getName());
			item.setId(stack.getId());
			item.setQuantity(stack.getQuantity());
			item.setPrice(getItemPrice(stack.getId()) * stack.getQuantity());
			lootList.add(item);
		}

		return lootList;
	}

	private int getItemPrice(int itemId)
	{
		return itemManager.getItemPrice(itemId);
	}

	private int getTeamSize()
	{
		int teamSize = 0;

		for (Player player : client.getPlayers())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}

			if (isPlayerInNexArea(player))
			{
				teamSize++;
			}
		}

		return Math.max(teamSize, 1);
	}

	private boolean shouldProcessNexChat(String message)
	{
		return isInNexArea() || isTrackedNexChatMessage(message);
	}

	private boolean isTrackedNexChatMessage(String message)
	{
		return KILL_COUNT_PATTERN.matcher(message).find()
			|| FIGHT_DURATION_PATTERN.matcher(message).find()
			|| MVP_SELF_PATTERN.matcher(message).matches()
			|| MVP_NAMED_PATTERN.matcher(message).matches()
			|| MVP_LEGACY_PATTERN.matcher(message).matches()
			|| UNIQUE_DROP_PATTERN.matcher(message).find()
			|| PET_PATTERN.matcher(message).find()
			|| isOwnNexlingMessage(message);
	}

	private boolean isOwnNexlingMessage(String message)
	{
		return isUntradeableNexlingMessage(message)
			|| (currentKill != null
				&& (isFirstPetMessage(message) || isDuplicatePetMessage(message)));
	}

	static boolean isFirstPetMessage(String message)
	{
		return message != null && FIRST_PET_PATTERN.matcher(message).matches();
	}

	static boolean isDuplicatePetMessage(String message)
	{
		return message != null && DUPLICATE_PET_PATTERN.matcher(message).matches();
	}

	static boolean isUntradeableNexlingMessage(String message)
	{
		return message != null && UNTRADEABLE_NEXLING_PATTERN.matcher(message).matches();
	}

	private boolean isNexNpcNearby()
	{
		for (NPC npc : client.getNpcs())
		{
			if (npc != null && NEX_NPC_IDS.contains(npc.getId()))
			{
				return true;
			}
		}

		return false;
	}

	private boolean isInNexArea()
	{
		final Player localPlayer = client.getLocalPlayer();
		return localPlayer != null && isPlayerInNexArea(localPlayer);
	}

	private boolean isPlayerInNexArea(Player player)
	{
		if (NEX_REGION_IDS.contains(player.getWorldLocation().getRegionID()))
		{
			return true;
		}

		if (!isNexNpcNearby())
		{
			return false;
		}

		final Player localPlayer = client.getLocalPlayer();
		return localPlayer != null
			&& player.getWorldView() != null
			&& localPlayer.getWorldView() != null
			&& player.getWorldView().getId() == localPlayer.getWorldView().getId();
	}

	private boolean isRelevantChatType(ChatMessageType type)
	{
		return type == ChatMessageType.GAMEMESSAGE
			|| type == ChatMessageType.SPAM
			|| type == ChatMessageType.FRIENDSCHATNOTIFICATION;
	}

	private void refreshKillListFromDisk()
	{
		if (!fileReadWriter.ensureProfile(client.getUsername(), client.getAccountHash()))
		{
			return;
		}

		SwingUtilities.invokeLater(panel::loadKillList);
	}

	private String getLocalPlayerName()
	{
		if (client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
		{
			return "";
		}

		return Text.toJagexName(client.getLocalPlayer().getName());
	}

	private void registerChatCommands()
	{
		chatCommandManager.registerCommandAsync(NEX_DRY_COMMAND, this::renderDryStreak, this::submitDryStreak);
		chatCommandManager.registerCommandAsync(NEX_DRY_STREAK_COMMAND, this::renderDryStreak, this::submitDryStreak);
		chatCommandManager.registerCommandAsync(NEX_LAST_COMMAND, this::renderLastItem, this::submitDryStreak);
		chatCommandManager.registerCommandAsync(NEX_LAST_ITEM_COMMAND, this::renderLastItem, this::submitDryStreak);
	}

	private void unregisterChatCommands()
	{
		chatCommandManager.unregisterCommand(NEX_DRY_COMMAND);
		chatCommandManager.unregisterCommand(NEX_DRY_STREAK_COMMAND);
		chatCommandManager.unregisterCommand(NEX_LAST_COMMAND);
		chatCommandManager.unregisterCommand(NEX_LAST_ITEM_COMMAND);
	}

	private boolean submitDryStreak(ChatInput chatInput, String value)
	{
		final String playerName = getLocalPlayerName();
		if (playerName.isEmpty())
		{
			return false;
		}

		final DryStreakStats stats = getLocalDryStreakStats();
		if (stats == null)
		{
			return false;
		}

		executor.execute(() ->
		{
			try
			{
				nexChatClient.submitDryStreak(playerName, stats);
			}
			catch (Exception ex)
			{
				log.warn("Unable to submit Nex dry streak", ex);
			}
			finally
			{
				chatInput.resume();
			}
		});

		return true;
	}

	private void renderDryStreak(ChatMessage chatMessage, String message)
	{
		DryStreakStats stats = lookupDryStreak(chatMessage);
		if (stats == null)
		{
			return;
		}

		String rendered = new ChatMessageBuilder()
			.append(NEX_DRY_COLOR, "Nex Dry Streak")
			.append(ChatColorType.HIGHLIGHT)
			.append(" - Personal: ")
			.append(Color.RED, Integer.toString(stats.getPersonalDry()))
			.append(ChatColorType.HIGHLIGHT)
			.append(" / Team: ")
			.append(Color.RED, Integer.toString(stats.getTeamDry()))
			.build();

		setChatMessage(chatMessage, rendered);
	}

	private void renderLastItem(ChatMessage chatMessage, String message)
	{
		DryStreakStats stats = lookupDryStreak(chatMessage);
		if (stats == null)
		{
			return;
		}

		String lastItem = stats.getLastPersonalItem();
		if (lastItem == null || lastItem.isEmpty())
		{
			lastItem = "n/a";
		}

		String rendered = new ChatMessageBuilder()
			.append(NEX_DRY_COLOR, "Nex Last Item: ")
			.append(Color.RED, lastItem)
			.build();

		setChatMessage(chatMessage, rendered);
	}

	private DryStreakStats lookupDryStreak(ChatMessage chatMessage)
	{
		final String player;
		if (chatMessage.getType() == ChatMessageType.PRIVATECHATOUT)
		{
			player = getLocalPlayerName();
		}
		else
		{
			player = Text.sanitize(chatMessage.getName());
		}

		if (player == null || player.isEmpty())
		{
			return null;
		}

		// Prefer local logs for your own command so the render matches the side panel.
		if (player.equalsIgnoreCase(getLocalPlayerName()))
		{
			return getLocalDryStreakStats();
		}

		try
		{
			return nexChatClient.getDryStreak(player);
		}
		catch (IOException ex)
		{
			log.debug("Unable to lookup Nex dry streak for {}", player, ex);
			return null;
		}
	}

	private DryStreakStats getLocalDryStreakStats()
	{
		if (!fileReadWriter.ensureProfile(client.getUsername(), client.getAccountHash()))
		{
			return null;
		}

		return DropRateCalculator.getDryStreakStats(fileReadWriter.readFromFile());
	}

	private void setChatMessage(ChatMessage chatMessage, String rendered)
	{
		final MessageNode messageNode = chatMessage.getMessageNode();
		clientThread.invokeLater(() ->
		{
			messageNode.setRuneLiteFormatMessage(rendered);
			client.refreshChat();
		});
	}
}
