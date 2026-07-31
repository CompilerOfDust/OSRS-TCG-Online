package com.thecardexchange.tcg.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.client.util.Text;

/**
 * Who this client is logged in as, and what shape they are in.
 *
 * The one payload every character endpoint speaks — linking, binding, heartbeats
 * and mode selection all send it, so the server sees a single kind of
 * observation whatever produced it.
 *
 * <p><b>Captured on the client thread, sent from the scheduler.</b> Reading
 * {@link Client} off the client thread is a data race; this type exists so the
 * read happens once, in the right place, and what travels to the network layer
 * is a finished immutable object. {@link #capture(Client)} is the only way to
 * build one from the game.
 */
@Value
public class CharacterSnapshot
{
	/**
	 * Worlds whose save is not the player's real account. A Leagues or beta world
	 * reports a different set of stats under the same name, which would read as an
	 * enormous XP spike followed by a regression — so nothing is sent from them at
	 * all. The server drops them too; this is the near half of that guard.
	 */
	private static final EnumSet<WorldType> EXCLUDED_WORLDS = EnumSet.of(
		WorldType.SEASONAL,
		WorldType.DEADMAN,
		WorldType.BETA_WORLD,
		WorldType.TOURNAMENT_WORLD,
		WorldType.NOSAVE_MODE,
		WorldType.FRESH_START_WORLD,
		WorldType.QUEST_SPEEDRUNNING,
		WorldType.PVP_ARENA);

	String rsn;
	/**
	 * The raw ACCOUNT_TYPE varbit (1777), sent as an int rather than a mapped name.
	 * Deliberately <b>not</b> {@code AccountType.values()[n]}: that enum has no
	 * UNRANKED_GROUP_IRONMAN member, so indexing it throws on a real account type
	 * that exists in game. Letting the server do the mapping also means a new
	 * account type needs no plugin release.
	 */
	int accountTypeId;
	/** Jagex's per-account id; 0 when unavailable. Survives an in-game rename. */
	long accountHash;
	int totalLevel;
	long totalXp;
	/** Skill display name → {level, xp}. Excludes the synthetic OVERALL entry. */
	Map<String, int[]> skills;
	/** World types this snapshot was taken on, so the server can double-check. */
	EnumSet<WorldType> worldTypes;

	/**
	 * Reads the logged-in character. <b>Must be called on the client thread.</b>
	 * Returns null when nobody is logged in or the stats aren't populated yet —
	 * the login tick fires before the skill table arrives, and a snapshot of zeroes
	 * would look like a catastrophic XP regression.
	 */
	@Nullable
	static CharacterSnapshot capture(Client client)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return null;
		}
		String name = local.getName();
		if (name == null || Text.sanitize(name).trim().isEmpty())
		{
			return null;
		}
		int totalLevel = client.getTotalLevel();
		if (totalLevel <= 0)
		{
			return null;
		}

		Map<String, int[]> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill == Skill.OVERALL)
			{
				// Not a skill — the server derives the total from the real ones.
				continue;
			}
			skills.put(skill.getName(), new int[]{
				client.getRealSkillLevel(skill),
				client.getSkillExperience(skill),
			});
		}

		long accountHash = 0L;
		try
		{
			accountHash = client.getAccountHash();
		}
		catch (RuntimeException ex)
		{
			// Not always available (and never on some login states). It is
			// corroboration for renames, never authorisation, so its absence
			// costs nothing.
		}

		return new CharacterSnapshot(
			Text.sanitize(name).trim(),
			client.getVarbitValue(Varbits.ACCOUNT_TYPE),
			accountHash,
			totalLevel,
			client.getOverallExperience(),
			skills,
			EnumSet.copyOf(client.getWorldType().isEmpty()
				? EnumSet.noneOf(WorldType.class)
				: client.getWorldType()));
	}

	/** True on a world whose stats must never be reported. */
	public boolean isExcludedWorld()
	{
		for (WorldType type : worldTypes)
		{
			if (EXCLUDED_WORLDS.contains(type))
			{
				return true;
			}
		}
		return false;
	}

	/** The wire shape the `character` object takes on every endpoint. */
	JsonObject toJson(String pluginVersion)
	{
		JsonObject json = new JsonObject();
		json.addProperty("rsn", rsn);
		json.addProperty("accountTypeId", accountTypeId);
		if (accountHash != 0L)
		{
			// A decimal string: the hash is 64-bit and JSON numbers are only exact
			// to 2^53, so a numeric field would silently corrupt it.
			json.addProperty("accountHash", Long.toUnsignedString(accountHash));
		}
		json.addProperty("totalLevel", totalLevel);
		json.addProperty("totalXp", totalXp);

		JsonObject skillsJson = new JsonObject();
		for (Map.Entry<String, int[]> entry : skills.entrySet())
		{
			JsonObject stat = new JsonObject();
			stat.addProperty("level", entry.getValue()[0]);
			stat.addProperty("xp", entry.getValue()[1]);
			skillsJson.add(entry.getKey(), stat);
		}
		json.add("skills", skillsJson);

		JsonArray worlds = new JsonArray();
		for (WorldType type : worldTypes)
		{
			worlds.add(type.name());
		}
		json.add("worldTypes", worlds);

		if (pluginVersion != null && !pluginVersion.isEmpty())
		{
			json.addProperty("pluginVersion", pluginVersion);
		}
		return json;
	}
}
