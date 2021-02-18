package de.Ryeera.DragoBot;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;

import org.json.JSONObject;

import de.Ryeera.libs.JSONUtils;
import de.Ryeera.libs.SQLConnector;

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
import net.dv8tion.jda.api.entities.Role;
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
import net.dv8tion.jda.api.utils.cache.CacheFlag;

public class DragoBot extends ListenerAdapter {

	private static final String VERSION = "1.1.0";

	private static File logFile;
	private static PrintWriter log;

	private static final File configFile = new File("dragobot.json");
	private static JSONObject config = new JSONObject();

	private static JDA jda;
	private static final EnumSet<Permission> readPerms = EnumSet.of(Permission.MESSAGE_READ);

	private static SQLConnector sql;

	public static void main(String[] args) {
		try {
			new File("logs").mkdir();
			logFile = new File("logs" + File.separator + "DragoBot_" + formatTime(System.currentTimeMillis(), "yyyyMMdd_HHmmss") + ".log");
			log = new PrintWriter(logFile);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		log("INFO", "Starting DragoBot v" + VERSION + "...");
		log("INFO", "Setting up Configuration...");
		try {
			config = JSONUtils.readJSON(configFile);
		} catch (IOException e) {
			log("SEVERE", "Couldn't read config-file! Halting...");
			logStackTrace(e);
			return;
		}

		log("INFO", "Setting up SQL-Connection...");
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

		log("INFO", "Setting up Discord-Connection...");
		JDABuilder builder = JDABuilder.create(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_VOICE_STATES);
		builder.enableCache(CacheFlag.MEMBER_OVERRIDES);
		builder.setToken(config.getString("token"));
		builder.setActivity(Activity.watching("the VoiceChannels"));
		builder.addEventListeners(new DragoBot());
		try {
			jda = builder.build();
			jda.awaitReady();
		} catch (LoginException | InterruptedException e) {
			logStackTrace(e);
			System.exit(1);
		}

		log("INFO", "Checking associations after startup...");
		for (Guild guild : jda.getGuilds()) {
			try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
				if (!guildConfig.first()) {
					register(guild);
				} else if (guildConfig.getBoolean("Initialized")) {
					initialize(guild);
				}
			} catch (SQLException e) {
				logStackTrace(e);
				log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			}
		}
		log("INFO", "Bot started!");
		Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
			for (Guild guild : jda.getGuilds()) {
				for (VoiceChannel vc : guild.getVoiceChannels()) {
					TextChannel tc = guild.getTextChannelById(vc.getId());
					for (PermissionOverride perm : tc.getMemberPermissionOverrides()) {
						if (!vc.getMembers().contains(perm.getMember())) {
							perm.delete().queue();
						}
					}
					for (Member member : vc.getMembers()) {
						if (!tc.canTalk(member)) {
							tc.putPermissionOverride(member).grant(readPerms).queue();
						}
					}
				}
			}
		}, 0, 60, TimeUnit.MINUTES);
	}

	public static String formatTime(long millisecs, String format) {
		return new SimpleDateFormat(format).format(new Date(millisecs));
	}

	public static void logStackTrace(Exception e) {
		log("EXCEPTION", e.toString());
		for (StackTraceElement s : e.getStackTrace()) {
			log("STACKTRACE", "at " + s.getClassName() + "." + s.getMethodName() + ":" + s.getLineNumber());
		}
	}

	public static void log(String level, String text) {
		System.out.println("[" + formatTime(System.currentTimeMillis(), "dd.MM.yy HH:mm:ss") + "] [" + level + "] " + text);
		log.println("[" + formatTime(System.currentTimeMillis(), "dd.MM.yy HH:mm:ss") + "] [" + level + "] " + text);
		log.flush();
	}

	public static ResultSet getGuildConfig(String guildID) throws SQLException {
		ResultSet guildConfig = sql.executeQuery("SELECT * FROM `Settings` WHERE `GuildID` = " + guildID);
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
			try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
				if (guildConfig.getBoolean("Initialized")) {
					try {
						TextChannel tc = guild.getTextChannelById(getAssociation(joined.getId()));
						if (guild.getAfkChannel() == null || !joined.getId().equals(guild.getAfkChannel().getId())) {
							log("INFO", "Processing join event for user \"" + member.getUser().getAsTag() + "\" in \""
									+ guild.getName() + " / " + joined.getName() + "\"...");
							tc.putPermissionOverride(member).grant(readPerms).queue();
							if (guildConfig.getBoolean("Logging")) {
								tc.sendMessage("**" + member.getEffectiveName() + "** joined the channel.").queue();
							}
						}
					} catch (SQLException e) {
						log("ERROR", guild.getName() + " / " + joined.getName() + "(" + guild.getId() + " / " + joined.getId() + " has no association!");
						logStackTrace(e);
					}
				}
			} catch (SQLException e1) {
				log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
				logStackTrace(e1);
				return;
			}
			if (toBeDeleted.containsKey(joined.getIdLong())) {
				toBeDeleted.get(joined.getIdLong()).cancel(true);
				toBeDeleted.remove(joined.getIdLong());
			}
		}
		if (left != null) {
			Guild guild = left.getGuild();
			try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
				if (guildConfig.getBoolean("Initialized")) {
					try {
						TextChannel tc = guild.getTextChannelById(getAssociation(left.getId()));
						if (guild.getAfkChannel() == null || !left.getId().equals(guild.getAfkChannel().getId())) {
							log("INFO", "Processing leave event for user \"" + member.getUser().getAsTag() + "\" in \""
									+ guild.getName() + " / " + left.getName() + "\"...");
							tc.getPermissionOverride(member).delete().queue();
							if (guildConfig.getBoolean("Logging")) {
								tc.sendMessage("**" + member.getEffectiveName() + "** left the channel.").queue();
							}
						}
					} catch (SQLException e) {
						log("ERROR", guild.getName() + " / " + left.getName() + "(" + guild.getId() + " / " + left.getId() + " has no association!");
						logStackTrace(e);
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
				log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
				logStackTrace(e1);
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
		try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
			if (guildConfig.getBoolean("Initialized")) {
				log("INFO", "VC \"" + vc.getName() + "\" created! Creating text-channel...");
				ChannelAction<TextChannel> action = guild
						.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
				if (vc.getParent() != null)
					action.setParent(vc.getParent());
				action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
				action.addPermissionOverride(guild.getSelfMember(), readPerms, null);
				action.addPermissionOverride(guild.getPublicRole(), null, readPerms);
				action.queue(tc -> {
					setAssociation(vc.getId(), tc.getId());
				});
			}
		} catch (SQLException e) {
			log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logStackTrace(e);
		}
	}

	@Override
	public void onVoiceChannelDelete(VoiceChannelDeleteEvent event) {
		VoiceChannel vc = event.getChannel();
		Guild guild = event.getGuild();
		try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
			if (guildConfig.getBoolean("Initialized")) {
				log("INFO", "VC \"" + vc.getName() + "\" deleted! Removing association...");
				removeAssociation(vc.getId());
			}
		} catch (SQLException e) {
			log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logStackTrace(e);
		}
	}

	@Override
	public void onTextChannelDelete(TextChannelDeleteEvent event) {
		Guild guild = event.getGuild();
		TextChannel tc = event.getChannel();
		try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
			if (guildConfig.getBoolean("Initialized")) {
				try (ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `tc` = " + tc.getId())) {
					if (association.first()) {
						log("WARN", "Text Channel for the Voice Channel with the ID " + association.getLong("vc")
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
					log("ERROR", guild.getName() + " / " + tc.getName() + "(" + guild.getId() + " / " + tc.getId() + " has no VoiceChannel!");
					logStackTrace(e);
				}
			}
		} catch (SQLException e) {
			log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logStackTrace(e);
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
			!guild.getPublicRole().hasPermission(c, Permission.MESSAGE_READ) && c.getName().contains("bot")).min(Comparator.naturalOrder()).orElse(guild.getDefaultChannel()
		);
		tc.sendMessage("__**Thanks for inviting me!**__\n\nTo start off, run the command `d!setup`!\n**I can't do anything until you do so!**").queueAfter(5, TimeUnit.SECONDS);
	}

	public static void initialize(Guild guild) {
		Role everyone = guild.getPublicRole();
		try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
			for (VoiceChannel vc : guild.getVoiceChannels()) {
				log("INFO", "Checking VC \"" + guild.getName() + " / " + vc.getName() + "\"...");
				String vcID = vc.getId();
				ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `vc` = " + vcID);
				try {
					if ((!association.first() || guild.getTextChannelById(association.getLong("tc")) == null) && (guild.getAfkChannel() == null || !vcID.equals(guild.getAfkChannel().getId()))) {
						log("INFO", "VC \"" + vc.getName() + "\" has no associated text-channel configured! Creating...");
						ChannelAction<TextChannel> action = guild.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
						if (vc.getParent() != null) {
							action.setParent(vc.getParent());
						}
						action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
						action.addPermissionOverride(everyone, null, readPerms);
						TextChannel tc = action.complete();
						sql.executeUpdate("INSERT INTO `Associations` (`vc`, `tc`) VALUES ('" + vcID + "', '" + tc.getId() + "')");
					}
				} catch (SQLException e) {
					log("ERROR", guild.getName() + " / " + vc.getName() + "(" + guild.getId() + " / " + vc.getId() + " has no association!");
					logStackTrace(e);
				}
				
			}
			sql.executeUpdate("UPDATE `Settings` SET `Initialized` = '1' WHERE `GuildID` = " + guild.getId());
		} catch (SQLException e1) {
			log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logStackTrace(e1);
		}
	}

	@Override
	public void onGuildJoin(GuildJoinEvent event) {
		Guild guild = event.getGuild();
		log("INFO", "Joined Guild \"" + guild.getName() + "\"! Sending setup-message...");
		register(guild);
	}

	@Override
	public void onGuildLeave(GuildLeaveEvent event) {
		log("INFO", "Left Guild \"" + event.getGuild().getName() + "\"! Removing Associations and config...");
		Guild guild = event.getGuild();
		sql.executeUpdate("DELETE FROM `Settings` WHERE `GuildID` = " + guild.getId());
		for (VoiceChannel vc : guild.getVoiceChannels()) {
			sql.executeUpdate("DELETE FROM `Associations` WHERE `vc` = " + vc.getId());
		}
	}

	public static MessageEmbed getSettingsEmbed(String guild) {
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
			log("ERROR", guild + " Config couldn't be loaded!");
			logStackTrace(e);
			eb.setDescription( "Sorry, I'm having problems accessing my config-database! If you can, please contact Ryeera about this.");
		}
		return eb.build();
	}

	public static MessageEmbed getHelpEmbed(String guild) throws SQLException {
		EmbedBuilder eb = new EmbedBuilder();
		eb.setAuthor("DragoBot");
		eb.setColor(0xD6F1FF);
		eb.setFooter("DragoBot Commands");
		eb.setThumbnail("https://i.ibb.co/zxV32sd/reeeex16-alt.png");
		eb.setTimestamp(Instant.now());
		eb.setTitle("DragoBot Commands");
		ResultSet guildConfig = getGuildConfig(guild);
		try {
			String prefix = guildConfig.getString("Prefix");
			eb.setDescription("Here are all the commands you can use:");
			eb.addField("__**Help**__", "**Usage:** `" + prefix + "help`\n**Description:** Shows this help-message.", false);
			eb.addField("__**Temporary VCs**__", "**Usage:** `" + prefix + "temp [name]`\n**Description:** Makes a new temporary Voice-Channel with the given name. This channel will be deleted there have no people been in it for 1 minute.", false);
			eb.addField("__**Settings**__", "**Usage:** `" + prefix + "settings <setting> <value>`\n**Description:** View or edit the settings. Without arguments, you see the settings. This can only be done by an Admin!", false);
		} catch (SQLException e) {
			logStackTrace(e);
			eb.setDescription("Sorry, I'm having problems accessing my database! If you can, please contact Ryeera about this.");
		} finally {
			if (guildConfig != null) {
				try {
					guildConfig.close();
				} catch (SQLException e) {}
			}
		}
		return eb.build();
	}

	@Override
	public void onGuildMessageReceived(GuildMessageReceivedEvent event) {
		Guild guild = event.getGuild();
		MessageChannel channel = event.getChannel();
		Member sender = event.getMember();
		String message = event.getMessage().getContentDisplay().toLowerCase();
		try (ResultSet guildConfig = getGuildConfig(guild.getId())) {
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
					channel.sendMessage(getHelpEmbed(guild.getId())).queue();
				} else if (message.equals("fixperms")) {
					for (VoiceChannel vc : guild.getVoiceChannels()) {
						
					}
					//TODO: Fix permissions for all assiciated text-channels.
				} else if (message.startsWith("debug ")) {
					message = message.substring(6);
					if (message.equals("servercount")) {
						channel.sendMessage("I am currently in **" + jda.getGuilds().size() + "** servers!").queue();
					}
				} else if (message.startsWith("broadcast ") && sender.getId().equals("553576678186680340")) {
					message = message.substring(10);
					for (Guild g : jda.getGuilds()) {
						TextChannel tc = g.getTextChannels().stream().filter(c -> 
							!g.getPublicRole().hasPermission(c, Permission.MESSAGE_READ) && c.getName().contains("bot")).min(Comparator.naturalOrder()).orElse(g.getDefaultChannel()
						);
						tc.sendMessage(message).queue();
					}
				} else if (sender.hasPermission(Permission.ADMINISTRATOR)) {
					if (message.equals("setup") && !guildConfig.getBoolean("Initialized")) {
						channel.sendMessage("Please edit the settings to your liking with `d!settings` and afterwards finish the setup with `d!initialize`!").queue();
					} else if (message.equals("initialize") && !guildConfig.getBoolean("Initialized")) {
						channel.sendMessage("Initialization in progress...").queue();
						initialize(guild);
						channel.sendMessage("Initialization complete!").queue();
					} else if (message.equals("settings")) {
						channel.sendMessage(getSettingsEmbed(guild.getId())).queue();
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
			log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logStackTrace(e);
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
