package de.Ryeera.DragoBot;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import org.json.JSONObject;

import de.Ryeera.libs.DragoLogger;
import de.Ryeera.libs.JSONUtils;
import de.Ryeera.libs.SQLConnector;
import de.Ryeera.libs.Utils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.events.channel.text.TextChannelDeleteEvent;
import net.dv8tion.jda.api.events.channel.voice.VoiceChannelCreateEvent;
import net.dv8tion.jda.api.events.channel.voice.VoiceChannelDeleteEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

public class DragoBot extends ListenerAdapter {

	private static final String VERSION = "1.1.0";

	private static DragoLogger logger;

	private static final File configFile = new File("dragobot.json");
	private static JSONObject config = new JSONObject();

	private static JDA jda;
	private static final EnumSet<Permission> readPerms = EnumSet.of(Permission.MESSAGE_READ);

	private static SQLConnector sql;

	public static void main(String[] args) {
		try {
			new File("logs").mkdir();
			logger = new DragoLogger(new File("logs" + File.separator + "DragoBot_" + Utils.formatTime(System.currentTimeMillis(), "yyyyMMdd_HHmmss") + ".log"));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		logger.log("INFO", "Starting DragoBot v" + VERSION + "...");
		logger.log("INFO", "Setting up Configuration...");
		try {
			config = JSONUtils.readJSON(configFile);
		} catch (IOException e) {
			logger.log("SEVERE", "Couldn't read config-file! Halting...");
			logger.logStackTrace(e);
			return;
		}

		logger.log("INFO", "Setting up SQL-Connection...");
		JSONObject db = config.getJSONObject("database");
		sql = new SQLConnector(db.getString("address"), db.getInt("port"), db.getString("username"), db.getString("password"), db.getString("database"));
		sql.executeUpdate("CREATE TABLE IF NOT EXISTS `Settings` ( " 
				+ "`GuildID` BIGINT UNSIGNED NOT NULL , "
				+ "`Initialized` BOOLEAN NOT NULL DEFAULT FALSE , " 
				+ "`Logging` BOOLEAN NOT NULL DEFAULT FALSE , "
				+ "`Prefix` VARCHAR(16) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'd!' , "
				+ "`Names` VARCHAR(100) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '{vc}-text' , "
				+ "`Descriptions` VARCHAR(1024) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'Text-Channel for everyone in the voice-channel [**{vc}**]' , "
				+ "PRIMARY KEY (`GuildID`)) " 
				+ "COMMENT = 'Contains all Settings for DragoBot';");
		sql.executeUpdate("CREATE TABLE IF NOT EXISTS `Associations` ( " 
				+ "`vc` BIGINT(20) UNSIGNED NOT NULL , "
				+ "`tc` BIGINT(20) UNSIGNED NOT NULL , " 
				+ "PRIMARY KEY (`vc`)) "
				+ "COMMENT = 'Contains all associations Text-Channels to Voice-Channels';");
		sql.executeUpdate("CREATE TABLE IF NOT EXISTS `Temps` ( " 
				+ "`id` BIGINT(20) UNSIGNED NOT NULL , "
				+ "PRIMARY KEY (`id`)) "
				+ "COMMENT = 'Contains all current temporary voice channels';");

		logger.log("INFO", "Setting up Discord-Connection...");
		JDABuilder builder = JDABuilder.create(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS);
		builder.enableCache(CacheFlag.MEMBER_OVERRIDES);
		builder.disableCache(CacheFlag.ACTIVITY, CacheFlag.EMOTE, CacheFlag.CLIENT_STATUS);
		builder.setToken(config.getString("token"));
		builder.setActivity(Activity.watching("the VoiceChannels"));
		builder.setMemberCachePolicy(MemberCachePolicy.ALL);
		builder.addEventListeners(new DragoBot());
		try {
			jda = builder.build();
			jda.awaitReady();
			for (Guild guild : jda.getGuilds()) {
				guild.loadMembers().onSuccess(m -> {
					logger.log("INFO", "Loaded " + m.size() + " members for " + guild.getName());
				});
			}
		} catch (LoginException | InterruptedException e) {
			logger.logStackTrace(e);
			System.exit(1);
		}
		
		Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
			logger.log("INFO", "Resyncing...");
			for (Guild guild : jda.getGuilds()) {
				try {
					resync(guild);
				} catch (Exception e) {
					logger.logStackTrace(e);
				}
			}
		}, 1, 60, TimeUnit.MINUTES);
		logger.log("INFO", "Bot started!");
	}

	public static ResultSet getGuildConfig(Guild guild) throws SQLException {
		ResultSet guildConfig = sql.executeQuery("SELECT * FROM `Settings` WHERE `GuildID` = " + guild.getId());
		guildConfig.next();
		return guildConfig;
	}

	private Map<Long, ScheduledFuture<?>> toBeDeleted = new HashMap<>();

	@Override
	public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
		VoiceChannel joined = event.getChannelJoined();
		VoiceChannel left = event.getChannelLeft();
		Member member = event.getEntity();
		if (joined != null) {
			Guild guild = joined.getGuild();
			try (ResultSet guildConfig = getGuildConfig(guild)) {
				if (guildConfig.getBoolean("Initialized")) {
					try {
						TextChannel tc = guild.getTextChannelById(getAssociation(joined.getId()));
						if (guild.getAfkChannel() == null || !joined.getId().equals(guild.getAfkChannel().getId())) {
							logger.log("INFO", "Processing join event for user \"" + member.getUser().getAsTag() + "\" in \""
									+ guild.getName() + " / " + joined.getName() + "\"...");
							tc.putPermissionOverride(member).grant(readPerms).queue();
							if (guildConfig.getBoolean("Logging")) {
								tc.sendMessage("**" + member.getEffectiveName() + "** joined the channel.").queue();
							}
						}
					} catch (SQLException e) {
						logger.log("ERROR", guild.getName() + " / " + joined.getName() + "(" + guild.getId() + " / " + joined.getId() + " has no association!");
						logger.logStackTrace(e);
					}
				}
			} catch (SQLException e1) {
				logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
				logger.logStackTrace(e1);
				return;
			}
			if (toBeDeleted.containsKey(joined.getIdLong())) {
				toBeDeleted.get(joined.getIdLong()).cancel(true);
				toBeDeleted.remove(joined.getIdLong());
			}
		}
		if (left != null) {
			Guild guild = left.getGuild();
			try (ResultSet guildConfig = getGuildConfig(guild)) {
				if (guildConfig.getBoolean("Initialized")) {
					try {
						TextChannel tc = guild.getTextChannelById(getAssociation(left.getId()));
						if (guild.getAfkChannel() == null || !left.getId().equals(guild.getAfkChannel().getId())) {
							logger.log("INFO", "Processing leave event for user \"" + member.getUser().getAsTag() + "\" in \""
									+ guild.getName() + " / " + left.getName() + "\"...");
							tc.getPermissionOverride(member).delete().queue();
							if (guildConfig.getBoolean("Logging")) {
								tc.sendMessage("**" + member.getEffectiveName() + "** left the channel.").queue();
							}
						}
					} catch (SQLException e) {
						logger.log("ERROR", guild.getName() + " / " + left.getName() + "(" + guild.getId() + " / " + left.getId() + " has no association!");
						logger.logStackTrace(e);
					}
				}
				if (isTemporaryVC(left.getId())) {
					if (left.getMembers().size() == 0) {
						TextChannel tc = guild.getTextChannelById(getAssociation(left.getId()));
						toBeDeleted.put(left.getIdLong(), left.delete().queueAfter(60, TimeUnit.SECONDS, s -> {
							tc.delete().queue();
							removeTemporaryVC(left.getId());
						}));
					}
				}
			} catch (SQLException e1) {
				logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
				logger.logStackTrace(e1);
				return;
			}
		}
		List<Long> toBeDeletedFromToBeDeleted = new ArrayList<>();
		for (long l : toBeDeleted.keySet()) {
			if (toBeDeleted.get(l).isDone() || toBeDeleted.get(l).isCancelled()) {
				toBeDeletedFromToBeDeleted.add(l);
			}
		}
		for (long l : toBeDeletedFromToBeDeleted) {
			toBeDeleted.remove(l);
		}
	}

	@Override
	public void onVoiceChannelCreate(VoiceChannelCreateEvent event) {
		VoiceChannel vc = event.getChannel();
		Guild guild = event.getGuild();
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			if (guildConfig.getBoolean("Initialized")) {
				logger.log("INFO", "VC \"" + vc.getName() + "\" created! Creating text-channel...");
				ChannelAction<TextChannel> action = guild.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
				if (vc.getParent() != null) {
					action.setParent(vc.getParent());
				}
				action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
				action.addPermissionOverride(guild.getSelfMember(), readPerms, null);
				action.addPermissionOverride(guild.getPublicRole(), null, readPerms);
				action.queue(tc -> {
					setAssociation(vc.getId(), tc.getId());
				});
			}
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
		}
	}

	@Override
	public void onVoiceChannelDelete(VoiceChannelDeleteEvent event) {
		VoiceChannel vc = event.getChannel();
		Guild guild = event.getGuild();
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			if (guildConfig.getBoolean("Initialized")) {
				logger.log("INFO", "VC \"" + vc.getName() + "\" deleted! Removing association...");
				removeAssociation(vc.getId());
			}
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
		}
	}

	@Override
	public void onTextChannelDelete(TextChannelDeleteEvent event) {
		Guild guild = event.getGuild();
		TextChannel tc = event.getChannel();
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			if (guildConfig.getBoolean("Initialized")) {
				try (ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `tc` = " + tc.getId())) {
					if (association.first()) {
						logger.log("WARN", "Text Channel for the Voice Channel with the ID " + association.getLong("vc")
								+ " was deleted... Recreating...");
						VoiceChannel vc = guild.getVoiceChannelById(association.getLong("vc"));
						ChannelAction<TextChannel> action = guild
								.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
						if (vc.getParent() != null)
							action.setParent(vc.getParent());
						action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
						action.addPermissionOverride(guild.getPublicRole(), null, readPerms);
						removeAssociation(vc.getId());
						setAssociation(vc.getId(), action.complete().getId());
					}
				} catch (SQLException e) {
					logger.log("ERROR", guild.getName() + " / " + tc.getName() + "(" + guild.getId() + " / " + tc.getId() + " has no VoiceChannel!");
					logger.logStackTrace(e);
				}
			}
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
		}
	}

	public static void register(Guild guild) {
		sql.executeUpdate("INSERT INTO `Settings` (`GuildID`, `Initialized`, `Logging`, `Prefix`, `Names`, `Descriptions`) VALUES ("
			+ "'" + guild.getId() + "', "
			+ "'0', "
			+ "'0', "
			+ "'d!', "
			+ "'{vc}-text', "
			+ "'Text-Channel for everyone in the voice-channel [**{vc}**]')");
		TextChannel tc = guild.getTextChannels().stream().filter(c -> 
			guild.getSelfMember().hasPermission(c, Permission.MESSAGE_WRITE) && c.getName().contains("bot")).min(Comparator.naturalOrder()).orElse(guild.getDefaultChannel()
		);
		tc.sendMessage(guild.getOwner().getAsMention() + "\n__**Thanks for inviting me!**__\n\nTo start off, run the command `d!setup`!\n**I can't do anything until you do so!**").queueAfter(5, TimeUnit.SECONDS);
	}

	public static void initialize(Guild guild) {
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			for (VoiceChannel vc : guild.getVoiceChannels()) {
				logger.log("INFO", "Checking VC \"" + guild.getName() + " / " + vc.getName() + "\"...");
				String vcID = vc.getId();
				ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `vc` = " + vcID);
				try {
					if ((!association.first() || guild.getTextChannelById(association.getLong("tc")) == null) && (guild.getAfkChannel() == null || !vcID.equals(guild.getAfkChannel().getId()))) {
						logger.log("INFO", "VC \"" + vc.getName() + "\" has no associated text-channel configured! Creating...");
						ChannelAction<TextChannel> action = guild.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
						if (vc.getParent() != null) {
							action.setParent(vc.getParent());
						}
						action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
						action.addPermissionOverride(guild.getPublicRole(), null, readPerms);
						TextChannel tc = action.complete();
						sql.executeUpdate("INSERT INTO `Associations` (`vc`, `tc`) VALUES ('" + vcID + "', '" + tc.getId() + "')");
					}
				} catch (SQLException e) {
					logger.log("ERROR", guild.getName() + " / " + vc.getName() + "(" + guild.getId() + " / " + vc.getId() + " has no association!");
					logger.logStackTrace(e);
				}
				
			}
			sql.executeUpdate("UPDATE `Settings` SET `Initialized` = '1' WHERE `GuildID` = " + guild.getId());
		} catch (SQLException e1) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e1);
		}
	}
	
	public static void resync(Guild guild) {
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			if (!guildConfig.first()) {
				register(guild);
			} else if (guildConfig.getBoolean("Initialized")) {
				initialize(guild);
			}
		} catch (SQLException e) {
			logger.logStackTrace(e);
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
		}
		for (VoiceChannel vc : guild.getVoiceChannels()) {
			if (guild.getAfkChannel() != null && vc.equals(guild.getAfkChannel())) continue;
			try {
				TextChannel tc = guild.getTextChannelById(getAssociation(vc.getId()));
				for (PermissionOverride perm : tc.getMemberPermissionOverrides()) {
					Member self = guild.getSelfMember();
					Member permmember = perm.getMember();
					List<Member> members = vc.getMembers();
					if (!permmember.equals(self) && !members.contains(permmember)) {
						perm.delete().queue();
					}
				}
				for (Member member : vc.getMembers()) {
					if (!tc.canTalk(member)) {
						tc.putPermissionOverride(member).grant(readPerms).queue();
					}
				}
			} catch (SQLException e) {
				try (ResultSet guildConfig = getGuildConfig(guild)) {
					if (guildConfig.getBoolean("Initialized")) {
						logger.log("INFO", "VC \"" + vc.getName() + "\" doesn't have an association while resyncing! Creating text-channel...");
						ChannelAction<TextChannel> action = guild.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
						if (vc.getParent() != null) {
							action.setParent(vc.getParent());
						}
						action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
						action.addPermissionOverride(guild.getSelfMember(), readPerms, null);
						action.addPermissionOverride(guild.getPublicRole(), null, readPerms);
						action.queue(tc -> {
							setAssociation(vc.getId(), tc.getId());
							for (Member member : vc.getMembers()) {
								if (!tc.canTalk(member)) {
									tc.putPermissionOverride(member).grant(readPerms).queue();
								}
							}
						});
					}
				} catch (SQLException ex) {
					logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
					logger.logStackTrace(ex);
				}
			}
		}
	}

	@Override
	public void onGuildJoin(GuildJoinEvent event) {
		Guild guild = event.getGuild();
		logger.log("INFO", "Joined Guild \"" + guild.getName() + "\"! Sending setup-message...");
		register(guild);
	}

	@Override
	public void onGuildLeave(GuildLeaveEvent event) {
		logger.log("INFO", "Left Guild \"" + event.getGuild().getName() + "\"! Removing Associations and config...");
		Guild guild = event.getGuild();
		sql.executeUpdate("DELETE FROM `Settings` WHERE `GuildID` = " + guild.getId());
		for (VoiceChannel vc : guild.getVoiceChannels()) {
			sql.executeUpdate("DELETE FROM `Associations` WHERE `vc` = " + vc.getId());
		}
	}

	public static MessageEmbed getSettingsEmbed(Guild guild) {
		EmbedBuilder eb = new EmbedBuilder();
		eb.setAuthor("DragoBot");
		eb.setColor(0xD6F1FF);
		eb.setFooter("DragoBot Settings");
		eb.setThumbnail("https://i.ibb.co/zxV32sd/reeeex16-alt.png");
		eb.setTimestamp(Instant.now());
		eb.setTitle("DragoBot Settings");
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			eb.setDescription("I am currently using the following settings.\nYou can change the settings with `" + guildConfig.getString("Prefix") + "settings [setting] [value]`");
			eb.addField("__**Prefix**__", "**Description:** The Prefix used for Commands.\n**Default:** `d!`\n**Value:** `" + guildConfig.getString("Prefix") + "`", false);
			eb.addField("__**Logging**__", "**Description:** Whether the bot logs each connect- and disconnect-event to the associated Text-Channel.\n**Default:** `false`\n**Value:** `" + String.valueOf(guildConfig.getBoolean("Logging")) + "`", false);
			eb.addField("__**Name**__", "**Description:** Name used for new Text-Channels. Will replace {vc} with the name of the associated Voice-Channel.\n**Default:** `{vc}-text`\n**Value:** `" + guildConfig.getString("Names") + "`", false);
			eb.addField("__**Description**__", "**Description:** Description used for new Text-Channels. Will replace {vc} with the name of the associated Voice-Channel.\n**Default:** `Text-Channel for everyone in the voice-channel [**{vc}**]`\n**Value:** `" + guildConfig.getString("Descriptions") + "`", false);
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
			eb.setDescription( "Sorry, I'm having problems accessing my config-database! If you can, please contact Ryeera about this.");
		}
		return eb.build();
	}

	public static MessageEmbed getHelpEmbed(Guild guild) throws SQLException {
		EmbedBuilder eb = new EmbedBuilder();
		eb.setAuthor("DragoBot");
		eb.setColor(0xD6F1FF);
		eb.setFooter("DragoBot Commands");
		eb.setThumbnail("https://i.ibb.co/zxV32sd/reeeex16-alt.png");
		eb.setTimestamp(Instant.now());
		eb.setTitle("DragoBot Commands");
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			String prefix = guildConfig.getString("Prefix");
			eb.setDescription("Here are all the commands you can use:");
			eb.addField("__**Help**__", "**Usage:** `" + prefix + "help`\n**Description:** Shows this help-message.", false);
			eb.addField("__**Temporary VCs**__", "**Usage:** `" + prefix + "temp [name]`\n**Description:** Makes a new temporary Voice-Channel with the given name. This channel will be deleted there have no people been in it for 1 minute.", false);
			eb.addField("__**Settings**__", "**Usage:** `" + prefix + "settings <setting> <value>`\n**Description:** View or edit the settings. Without arguments, you see the settings. This can only be done by an Admin!", false);
			eb.addField("__**Manual Resync**__", "**Usage** `" + prefix + "resync`\n**Description:** Manually resync this server. This will check all voice-channels for if they have a text-channel as well as check all permission-overrides for if they are up-to-date. This happens automatically every hour.", false);
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
			eb.setDescription("Sorry, I'm having problems accessing my database! If you can, please contact Ryeera about this.");
		}
		return eb.build();
	}

	@Override
	public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
		Guild guild = event.getGuild();
		MessageChannel channel = event.getChannel();
		Member sender = event.getMember();
		String message = event.getMessage().getContentDisplay().toLowerCase();
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			if (message.startsWith(guildConfig.getString("Prefix"))) {
				message = message.substring(guildConfig.getString("Prefix").length());
				if (message.startsWith("temp")) {
					final String name = message.substring(5);
					if (guild.getCategoriesByName("Temp", true).size() > 0) {
						guild.getCategoriesByName("Temp", true).get(0).createVoiceChannel(name).queue(v -> {
							addTemporaryVC(v.getId());
						});
					} else {
						guild.createCategory("Temp").queue(c -> {
							c.createVoiceChannel(name).queue(v -> {
								addTemporaryVC(v.getId());
							});
						});
					}
				} else if (message.equals("help")) {
					channel.sendMessage(getHelpEmbed(guild)).queue();
				} else if (message.equals("fixperms")) {
					for (VoiceChannel vc : guild.getVoiceChannels()) {
						try {
							TextChannel tc = guild.getTextChannelById(getAssociation(vc.getId()));
							tc.putPermissionOverride(guild.getSelfMember()).grant(readPerms).queue();
						} catch (SQLException e) {
							logger.log("ERROR", guild.getName() + " / " + vc.getName() + "(" + guild.getId() + " / " + vc.getId() + " has no association!");
							logger.logStackTrace(e);
						}
					}
					channel.sendMessage("Your server is now up-to-date and remove my Admin-Permissions!").queueAfter(10, TimeUnit.SECONDS);
				} else if (message.equals("resync")) {
					channel.sendMessage("Manually resyncing this server...").queue();
					resync(guild);
					channel.sendMessage("Done resyncing!").queueAfter(5, TimeUnit.SECONDS);
				} else if (message.startsWith("debug ")) {
					message = message.substring(6);
					if (message.equals("servercount")) {
						channel.sendMessage("I am currently in **" + jda.getGuilds().size() + "** servers!").queue();
					}
				} else if (message.startsWith("broadcast ") && sender.getId().equals("553576678186680340")) {
					message = message.substring(10);
					for (Guild g : jda.getGuilds()) {
						TextChannel tc = g.getTextChannels().stream().filter(c -> 
							!g.getSelfMember().hasPermission(c, Permission.MESSAGE_WRITE) && c.getName().contains("bot")).min(Comparator.naturalOrder()).orElse(g.getDefaultChannel()
						);
						tc.sendMessage(message.replace("[OWNER]", g.getOwner().getAsMention())).queue();
					}
				} else if (sender.hasPermission(Permission.ADMINISTRATOR)) {
					if (message.equals("setup") && !guildConfig.getBoolean("Initialized")) {
						channel.sendMessage("Please edit the settings to your liking with `d!settings` and afterwards finish the setup with `d!initialize`!").queue();
					} else if (message.equals("initialize") && !guildConfig.getBoolean("Initialized")) {
						channel.sendMessage("Initialization in progress...").queue();
						initialize(guild);
						channel.sendMessage("Initialization complete!").queue();
					} else if (message.equals("settings")) {
						channel.sendMessage(getSettingsEmbed(guild)).queue();
					} else if (message.startsWith("settings ")) {
						message = message.substring(9);
						if (message.equals("prefix")) {
							channel.sendMessage("My prefix in this server is `" + guildConfig.getString("Prefix") + "`. You can change it with `" + guildConfig.getString("Prefix") + "settings prefix [new prefix]`.").queue();
						} else if (message.equals("logging")) {
							channel.sendMessage("Logging is currently `" + (guildConfig.getBoolean("Logging") ? "en" : "dis") + "abled`. You can " + (guildConfig.getBoolean("Logging") ? "dis" : "en") + "able it with `" + guildConfig.getString("Prefix") + "settings logging " + (guildConfig.getBoolean("Logging") ? "dis" : "en") + "able`.").queue();
						} else if (message.equals("name")) {
							channel.sendMessage("The name for new text-channels is currently `" + guildConfig.getString("Names") + "`. You can change it with `" + guildConfig.getString("Prefix") + "settings name [new name]`.").queue();
						} else if (message.equals("description")) {
							channel.sendMessage("The description for new text-channels is currently `" + guildConfig.getString("Descriptions") + "`. You can change it with `" + guildConfig.getString("Prefix") + "settings description [new description]`.").queue();
						} else if (message.startsWith("prefix ")) {
							message = message.substring(7);
							sql.executeUpdate("UPDATE `Settings` SET `Prefix` = '" + message + "' WHERE `GuildID` = " + guild.getId());
							channel.sendMessage("Prefix set to `" + message + "`.").queue();
						} else if (message.startsWith("logging ")) {
							message = message.substring(8);
							if (message.equals("enable") || message.equals("enabled") || message.equals("true")) {
								sql.executeUpdate("UPDATE `Settings` SET `Logging` = '1' WHERE `GuildID` = " + guild.getId());
								channel.sendMessage("**Logging** has been **enabled**!").queue();
							} else if (message.equals("disable") || message.equals("disabled") || message.equals("false")) {
								sql.executeUpdate("UPDATE `Settings` SET `Logging` = '0' WHERE `GuildID` = " + guild.getId());
								channel.sendMessage("**Logging** has been **disabled**!").queue();
							} else {
								channel.sendMessage("Invalid input! Valid values for `logging` are `enable` and `disable`!").queue();
							}
						} else if (message.startsWith("name ")) {
							message = message.substring(5);
							if (message.length() > 100) {
								channel.sendMessage("The maximum length for a channel-name is 100 characters! Your name was " + message.length() + " characters long.").queue();
							} else {
								sql.executeUpdate("UPDATE `Settings` SET `Names` = '" + message + "' WHERE `GuildID` = " + guild.getId());
								channel.sendMessage("Name for new Text-Channels set to `" + message + "`.").queue();
							}
						} else if (message.startsWith("description ")) {
							message = message.substring(12);
							if (message.length() > 1024) {
								channel.sendMessage("The maximum length for a channel-description is 1024 characters! Your description was " + message.length() + " characters long.").queue();
							} else {
								sql.executeUpdate("UPDATE `Settings` SET `Descriptions` = '" + message + "' WHERE `GuildID` = " + guild.getId());
								channel.sendMessage("Name for new Text-Channels set to `" + message + "`.").queue();
							}
						}
					}
				}
			}
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
		}
	}

	public static String getAssociation(String vcID) throws SQLException {
		ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `vc` = " + vcID);
		association.next();
		String returns = association.getString("tc");
		association.close();
		return returns;
	}

	public static void removeTemporaryVC(String vcID) {
		sql.executeUpdate("DELETE FROM `Temps` WHERE `id`= " + vcID);
	}

	public static boolean isTemporaryVC(String vcID) {
		try (ResultSet results = sql.executeQuery("SELECT * FROM `Temps` WHERE `id` = " + vcID)) {
			return results.next();
		} catch (SQLException e) {
			return false;
		}
	}

	public static void addTemporaryVC(String vcID) {
		sql.executeUpdate("INSERT INTO `Temps` (`id`) VALUES ('" + vcID + "')");
	}

	public static void setAssociation(String vcID, String tcID) {
		sql.executeUpdate("INSERT INTO `Associations` (`vc`, `tc`) VALUES ('" + vcID + "', '" + tcID + "')");
	}

	public static void removeAssociation(String vcID) {
		sql.executeUpdate("DELETE FROM `Associations` WHERE `vc` = " + vcID);
	}
}
