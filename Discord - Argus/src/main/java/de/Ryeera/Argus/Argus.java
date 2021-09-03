package de.Ryeera.Argus;

import java.io.File;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

public class Argus extends ListenerAdapter {

	private static final String VERSION = "1.2.0";

	private static DragoLogger logger;

	private static final File configFile = new File("argus.json");
	private static JSONObject config = new JSONObject();

	private static JDA jda;
	private static final EnumSet<Permission> readPerms = EnumSet.of(Permission.MESSAGE_READ);
	private static final EnumSet<Permission> selfPerms = EnumSet.of(Permission.MESSAGE_READ, Permission.MESSAGE_WRITE, Permission.MANAGE_CHANNEL, Permission.MESSAGE_EMBED_LINKS);

	private static SQLConnector sql;

	public static void main(String[] args) {
		try {
			new File("logs").mkdir();
			logger = new DragoLogger(new File("logs" + File.separator + "Argus_" + Utils.formatTime(System.currentTimeMillis(), "yyyyMMdd_HHmmss") + ".log"));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		logger.log("INFO", "Starting Argus v" + VERSION + "...");
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
				+ "`Prefix` VARCHAR(32) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'a!' , "
				+ "`Names` VARCHAR(100) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT '{vc}-text' , "
				+ "`Descriptions` VARCHAR(1024) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT 'Text-Channel for everyone in the voice-channel [**{vc}**]' , "
				+ "`TempCatID` BIGINT(20) UNSIGNED NOT NULL DEFAULT '0' , "
				+ "PRIMARY KEY (`GuildID`)) " 
				+ "COMMENT = 'Contains all Settings for Argus';");
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
		JDABuilder builder = JDABuilder.create(GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_VOICE_STATES);
		builder.enableCache(CacheFlag.MEMBER_OVERRIDES);
		builder.disableCache(CacheFlag.ACTIVITY, CacheFlag.EMOTE, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS, CacheFlag.ROLE_TAGS);
		builder.setToken(config.getString("token"));
		builder.setActivity(Activity.watching("the VoiceChannels"));
		builder.addEventListeners(new Argus());
		try {
			jda = builder.build();
			jda.awaitReady();
		} catch (LoginException | InterruptedException e) {
			logger.logStackTrace(e);
			System.exit(1);
		}
		
		Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
			logger.log("INFO", "Resyncing...");
			for (Guild guild : jda.getGuilds()) {
				logger.log("INFO", "Resyncing " + guild.getName() + " (" + guild.getId() + ")...");
				 try {
					resync(guild);
				} catch (Exception e) {
					logger.logStackTrace(e);
				}
			}
			logger.log("INFO", "Resync complete!");
		}, 1, 360, TimeUnit.MINUTES);
		logger.log("INFO", "Argus started! I'm watching...");
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
							tc.putPermissionOverride(member).grant(readPerms).queue(success -> {}, failure -> {
								tc.sendMessage("Yo I can't add Permission-Overrides to this channel! Please edit this channel so I explicitely have the `Manage Permissions` permission!").queue();
							});
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
							try {
								tc.getPermissionOverride(member).delete().queue();
							} catch (NullPointerException e) {}
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
				if (createTextChannel(vc, guild, guildConfig)) {
					logger.log("INFO", "Text-Channel created!");
				} else {
					logger.log("WARN", "Text-Channel could not be created!");
				}
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
						VoiceChannel vc = guild.getVoiceChannelById(association.getLong("vc"));
						removeAssociation(vc.getId());
						if (guild.getAfkChannel() != null && vc.equals(guild.getAfkChannel())) return;
						logger.log("WARN", "Text Channel for the Voice Channel \"" + vc.getName() + "\" was deleted... Recreating...");
						if (createTextChannel(vc, guild, guildConfig)) {
							logger.log("INFO", "Text-Channel created!");
						} else {
							logger.log("WARN", "Text-Channel could not be created!");
						}
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
	
	public static boolean createTextChannel(VoiceChannel vc, Guild guild, ResultSet guildConfig) {
		try {
			logger.log("INFO", "VC \"" + vc.getName() + "\" has no associated text-channel configured! Creating...");
			ChannelAction<TextChannel> action = guild.createTextChannel(guildConfig.getString("Names").replace("{vc}", vc.getName()));
			action.setTopic(guildConfig.getString("Descriptions").replace("{vc}", vc.getName()));
			action.addPermissionOverride(guild.getSelfMember(), selfPerms, null);
			action.addPermissionOverride(guild.getPublicRole(), null, readPerms);
			if (vc.getParent() != null) {
				action.setParent(vc.getParent());
			}
			setAssociation(vc.getId(), action.complete().getId());
			return true;
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
			return false;
		} catch (Exception e) {
			logger.log("ERROR", "Couldn't create TextChannel for vc \"" + guild.getName() + " / " + vc.getName());
			logger.logStackTrace(e);
			return false;
		}
	}

	public static void register(Guild guild) {
		try {
			guild.createTextChannel("argus").addPermissionOverride(guild.getSelfMember(), selfPerms, null).addPermissionOverride(guild.getPublicRole(), null, readPerms).queue(tc -> {
				guild.retrieveOwner().queue(owner -> {
					tc.sendMessage(owner.getAsMention() + "\n__**Thanks for inviting me!**__\n\nTo start off, run the command `a!setup`!\n**I can't do anything until you do so!**").queueAfter(5, TimeUnit.SECONDS);
				});
			});
		} catch (InsufficientPermissionException e) {
			e.printStackTrace();
			TextChannel tc = null;
			for (TextChannel channel : guild.getTextChannels()) {
				if (guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_WRITE)) {
					tc = channel;
					break;
				}
			}
			if (tc == null) return;
			final TextChannel tcf = tc;
			guild.retrieveOwner().queue(owner -> {
				tcf.sendMessage(owner.getAsMention() + "\n__**Thanks for inviting me!**__\n\nTo start off, run the command `a!setup`!\n**I can't do anything until you do so!**").queue();
				tcf.sendMessage("I just realized that I'm missing permissions! I can only do my job properly if I have the following permissions:\n- View Channels\n- Manage Channels\n- Manage Roles\n- Send Messages\n- Embed Links\n- Move Members\n\nIf you wanna know why I need these permissions, check <https://github.com/Ryeera/Argus>!").queue();
			});
		}
		sql.executeUpdate("INSERT INTO `Settings` (`GuildID`, `Initialized`, `Logging`, `Prefix`, `Names`, `Descriptions`) VALUES ("
			+ "'" + guild.getId() + "', "
			+ "'0', "
			+ "'0', "
			+ "'a!', "
			+ "'{vc}-text', "
			+ "'Text-Channel for everyone in the voice-channel [**{vc}**]')");
	}

	public static void initialize(Guild guild) {
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			for (VoiceChannel vc : guild.getVoiceChannels()) {
				String vcID = vc.getId();
				ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `vc` = " + vcID);
				try {
					if ((!association.first() || guild.getTextChannelById(association.getLong("tc")) == null) && (guild.getAfkChannel() == null || !vcID.equals(guild.getAfkChannel().getId()))) {
						if (createTextChannel(vc, guild, guildConfig)) {
							logger.log("INFO", "Text-Channel created!");
						} else {
							logger.log("WARN", "Text-Channel could not be created!");
						}
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
				for (VoiceChannel vc : guild.getVoiceChannels()) {
					if (guild.getAfkChannel() != null && vc.equals(guild.getAfkChannel())) continue;
					try {
						TextChannel tc = guild.getTextChannelById(getAssociation(vc.getId()));
						//TODO: Remove after a while
						if (guild.getBotRole() != null) {
							for (PermissionOverride perm : tc.getRolePermissionOverrides()) {
								if (perm.getRole().equals(guild.getBotRole())) {
									tc.putPermissionOverride(guild.getSelfMember()).grant(selfPerms).queue();
									perm.delete().queueAfter(10, TimeUnit.SECONDS);
									break;
								}
							}
						}
						for (PermissionOverride perm : tc.getMemberPermissionOverrides()) {
							Member permmember = perm.getMember();
							if (!vc.getMembers().contains(permmember) && !permmember.equals(guild.getSelfMember())) {
								perm.delete().queue();
							}
						}
						for (Member member : vc.getMembers()) {
							if (!tc.canTalk(member)) {
								tc.putPermissionOverride(member).grant(readPerms).queue(success -> {}, failure -> {
									tc.sendMessage("Yo I can't add Permission-Overrides to this channel! Please edit this channel so I explicitely have the `Manage Permissions` permission!").queue();
								});
							}
						}
						boolean permswrong = false;
						if (!guild.getSelfMember().hasPermission(tc, Permission.VIEW_CHANNEL)) {
							permswrong = true;
						} else if (!guild.getSelfMember().hasPermission(tc, Permission.MESSAGE_WRITE)) {
							permswrong = true;
						} else if (!guild.getSelfMember().hasPermission(tc, Permission.MANAGE_CHANNEL)) {
							permswrong = true;
						} else if (!guild.getSelfMember().hasPermission(tc, Permission.MANAGE_PERMISSIONS)) {
							permswrong = true;
						} else if (!guild.getSelfMember().hasPermission(tc, Permission.MESSAGE_EMBED_LINKS)) {
							permswrong = true;
						}
						if (permswrong) {
							try {
								tc.upsertPermissionOverride(guild.getSelfMember()).grant(selfPerms).queue();
							} catch (InsufficientPermissionException e) {
								try {
									guild.createTextChannel("argus").addPermissionOverride(guild.getSelfMember(), selfPerms, null).addPermissionOverride(guild.getPublicRole(), null, readPerms).queue(nc -> {
										String prefix = "a!";
										try {
											prefix = guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ");
										} catch (SQLException e1) {}
										nc.sendMessage("Hey there! I got permission-issues with " + tc.getAsMention() + "! Please run " + prefix + "debug permcheck to run a detailed permission-checkup on your server. Please readd all missing server- and channel-permissions! Thank youuuuu!").queue();
									});
								} catch (InsufficientPermissionException ex) {
									TextChannel nc = null;
									for (TextChannel channel : guild.getTextChannels()) {
										if (guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_WRITE)) {
											nc = channel;
											break;
										}
									}
									if (nc != null) {
										String prefix = "a!";
										try {
											prefix = guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ");
										} catch (SQLException e1) {}
										nc.sendMessage("Hey there! I got permission-issues with " + tc.getAsMention() + "! Please run " + prefix + "debug permcheck to run a detailed permission-checkup on your server. Please readd all missing server- and channel-permissions! Thank youuuuu!").queue();
									}
								}
							}
						}
					} catch (SQLException e) {
						logger.log("ERROR", guild.getName() + " / " + vc.getName() + "(" + guild.getId() + " / " + vc.getId() + " has no association!");
						logger.logStackTrace(e);
					}
				}
			}
		} catch (SQLException e) {
			logger.logStackTrace(e);
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
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
		eb.setAuthor("Argus");
		eb.setColor(0xD6F1FF);
		eb.setFooter("I am watching...");
		eb.setThumbnail("https://i.ibb.co/zxV32sd/reeeex16-alt.png");
		eb.setTimestamp(Instant.now());
		eb.setTitle("Argus Settings");
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			eb.setDescription("I am currently using the following settings.\nYou can change the settings with `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "settings [setting] [value]`");
			eb.addField("__**Prefix**__", "**Description:** The Prefix used for Commands.\n**Default:** `a!`\n**Value:** `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "`", false);
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
		eb.setAuthor("Argus");
		eb.setColor(0xD6F1FF);
		eb.setFooter("I am watching...");
		eb.setThumbnail("https://i.ibb.co/zxV32sd/reeeex16-alt.png");
		eb.setTimestamp(Instant.now());
		eb.setTitle("Argus Commands");
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			String prefix = guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ");
			eb.setDescription("Here are all the commands you can use:");
			eb.addField("__**Help**__", "**Usage:** `" + prefix + "help`\n**Description:** Shows this help-message.", false);
			eb.addField("__**Temporary VCs**__", "**Usage:** `" + prefix + "temp [name]`\n**Description:** Makes a new temporary Voice-Channel with the given name. This channel will be deleted, once there have been no people in it for 1 minute.", false);
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
		String message = event.getMessage().getContentRaw();
		try (ResultSet guildConfig = getGuildConfig(guild)) {
			if (!guildConfig.first()) {
				register(guild);
				channel.sendMessage("Apparently your guild wasn't registered yet! This happens when you invite me while I'm offline. No problem though, I will do that for you now! Please wait a minute and then try your command again!").queue();
				return;
			}
			if (message.startsWith(guildConfig.getString("Prefix"))) {
				message = message.substring(guildConfig.getString("Prefix").length()).trim();
				if (message.startsWith("temp")) {
					final String name = message.substring(5);
					if (guild.getCategoryById(guildConfig.getString("TempCatID")) != null) {
						guild.getCategoryById(guildConfig.getString("TempCatID")).createVoiceChannel(name).queue(v -> {
							addTemporaryVC(v.getId());
						});
					} else {
						guild.createCategory("Temp Channels").queue(c -> {
							sql.executeUpdate("UPDATE `Settings` SET `TempCatID` = '" + c.getIdLong() + "' WHERE `GuildID` = " + guild.getId());
							c.createVoiceChannel(name).queue(v -> {
								addTemporaryVC(v.getId());
							});
						});
					}
				} else if (message.equalsIgnoreCase("help")) {
					channel.sendMessageEmbeds(getHelpEmbed(guild)).queue();
				} else if (sender.hasPermission(Permission.ADMINISTRATOR)) {
					if (message.equalsIgnoreCase("setup") && !guildConfig.getBoolean("Initialized")) {
						channel.sendMessage("Please edit the settings to your liking with `a!settings` and afterwards finish the setup with `a!initialize`!").queue();
					} else if (message.equalsIgnoreCase("fixperms")) {
						channel.sendMessage("Fixing permissions to be able to work without Admin-Permissions...").queue();
						for (VoiceChannel vc : guild.getVoiceChannels()) {
							if (guild.getAfkChannel() != null && vc.getId().equals(guild.getAfkChannel().getId())) continue;
							try {
								TextChannel tc = guild.getTextChannelById(getAssociation(vc.getId()));
								tc.putPermissionOverride(guild.getSelfMember()).grant(selfPerms).queue();
							} catch (Exception e) {
								logger.log("ERROR", guild.getName() + " / " + vc.getName() + "(" + guild.getId() + " / " + vc.getId() + " has no association!");
								logger.logStackTrace(e);
							}
						}
						channel.sendMessage("Your server is now up-to-date and you can remove my Admin-Permissions! Remember to instead give me the following permissions:\n"
								+ "- View Channels\n"
								+ "- Manage Channels\n"
								+ "- Manage Roles\n"
								+ "- Send Messages\n"
								+ "- Embed Links\n"
								+ "- Move Members\n\n"
								+ "If you wanna know why I need these permissions, check <https://github.com/Ryeera/Argus>!").queueAfter(10, TimeUnit.SECONDS);
					} else if (message.equalsIgnoreCase("resync")) {
						channel.sendMessage("Manually resyncing this server...").queue();
						resync(guild);
						channel.sendMessage("Done resyncing!").queueAfter(5, TimeUnit.SECONDS);
					} else if (message.startsWith("debug ")) {
						message = message.substring(6);
						if (message.equalsIgnoreCase("servercount")) {
							channel.sendMessage("I am currently in **" + jda.getGuilds().size() + "** servers!").queue();
						} else if (message.equalsIgnoreCase("permcheck")) {
							String send1 = "__**Server-Permissions:**__";
							if (guild.getSelfMember().hasPermission(Permission.VIEW_CHANNEL)) {
								send1 += "\n✅ View Channels";
							} else {
								send1 += "\n⛔ View Channels";
							}
							if (guild.getSelfMember().hasPermission(Permission.MESSAGE_WRITE)) {
								send1 += "\n✅ Send Messages";
							} else {
								send1 += "\n⛔ Send Messages";
							}
							if (guild.getSelfMember().hasPermission(Permission.MESSAGE_EMBED_LINKS)) {
								send1 += "\n✅ Embed Links";
							} else {
								send1 += "\n⛔ Embed Links";
							}
							if (guild.getSelfMember().hasPermission(Permission.MANAGE_CHANNEL)) {
								send1 += "\n✅ Manage Channels";
							} else {
								send1 += "\n⛔ Manage Channels";
							}
							if (guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES)) {
								send1 += "\n✅ Manage Roles";
							} else {
								send1 += "\n⛔ Manage Roles";
							}
							if (guild.getSelfMember().hasPermission(Permission.VOICE_MOVE_OTHERS)) {
								send1 += "\n✅ Move Members";
							} else {
								send1 += "\n⛔ Move Members";
							}
							channel.sendMessage(send1).queue(m1 -> {
								channel.sendMessage("__**Channel-Permissions:**__").queueAfter(3, TimeUnit.SECONDS, m2 -> {
									for (VoiceChannel vc : guild.getVoiceChannels()) {
										String send2 = "**Voice-Channel:** " + vc.getAsMention();
										try {
											TextChannel tc = guild.getTextChannelById(getAssociation(vc.getId()));
											if (tc == null) {
												send2 += "\nNo associated Text-Channel found!";
											} else {
												send2 += "\nAssociated text-channel: " + tc.getAsMention();
												if (guild.getSelfMember().hasPermission(tc, Permission.VIEW_CHANNEL)) {
													send2 += "\n✅ View Channel";
												} else {
													send2 += "\n⛔ View Channel";
												}
												if (guild.getSelfMember().hasPermission(tc, Permission.MESSAGE_WRITE)) {
													send2 += "\n✅ Send Messages";
												} else {
													send2 += "\n⛔ Send Messages";
												}
												if (guild.getSelfMember().hasPermission(tc, Permission.MANAGE_CHANNEL)) {
													send2 += "\n✅ Manage Channel";
												} else {
													send2 += "\n⛔ Manage Channel";
												}
												if (guild.getSelfMember().hasPermission(tc, Permission.MANAGE_PERMISSIONS)) {
													send2 += "\n✅ Manage Permissions";
												} else {
													send2 += "\n⛔ Manage Permissions";
												}
												if (guild.getSelfMember().hasPermission(tc, Permission.MESSAGE_EMBED_LINKS)) {
													send2 += "\n✅ Embed Links";
												} else {
													send2 += "\n⛔ Embed Links";
												}
											}
										} catch (SQLException e) {
											send2 += "\nNo associated Text-Channel found!";
										}
										channel.sendMessage(send2).queue();
									}
								});
							});
						}
					} else if (message.equalsIgnoreCase("initialize") && !guildConfig.getBoolean("Initialized")) {
						channel.sendMessage("Initialization in progress...").queue();
						initialize(guild);
						channel.sendMessage("Setup complete!").queueAfter(6, TimeUnit.SECONDS);
					} else if (message.equalsIgnoreCase("settings")) {
						channel.sendMessageEmbeds(getSettingsEmbed(guild)).queue();
					} else if (message.startsWith("settings ")) {
						message = message.substring(9);
						if (message.equalsIgnoreCase("prefix")) {
							channel.sendMessage("My prefix in this server is `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "`. You can change it with `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "settings prefix [new prefix]`.").queue();
						} else if (message.equalsIgnoreCase("logging")) {
							channel.sendMessage("Logging is currently `" + (guildConfig.getBoolean("Logging") ? "en" : "dis") + "abled`. You can " + (guildConfig.getBoolean("Logging") ? "dis" : "en") + "able it with `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "settings logging " + (guildConfig.getBoolean("Logging") ? "dis" : "en") + "able`.").queue();
						} else if (message.equalsIgnoreCase("name")) {
							channel.sendMessage("The name for new text-channels is currently `" + guildConfig.getString("Names") + "`. You can change it with `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "settings name [new name]`.").queue();
						} else if (message.equalsIgnoreCase("description")) {
							channel.sendMessage("The description for new text-channels is currently `" + guildConfig.getString("Descriptions") + "`. You can change it with `" + guildConfig.getString("Prefix").replace("<@!655496558095237130>", "@Argus ") + "settings description [new description]`.").queue();
						} else if (message.startsWith("prefix ")) {
							message = message.substring(7);
							sql.executeUpdate("UPDATE `Settings` SET `Prefix` = '" + message + "' WHERE `GuildID` = " + guild.getId());
							channel.sendMessage("Prefix set to `" + message.replace("<@!655496558095237130>", "@Argus ") + "`.").queue();
						} else if (message.startsWith("logging ")) {
							message = message.substring(8);
							if (message.equalsIgnoreCase("enable") || message.equalsIgnoreCase("enabled") || message.equalsIgnoreCase("true")) {
								sql.executeUpdate("UPDATE `Settings` SET `Logging` = '1' WHERE `GuildID` = " + guild.getId());
								channel.sendMessage("**Logging** has been **enabled**!").queue();
							} else if (message.equalsIgnoreCase("disable") || message.equalsIgnoreCase("disabled") || message.equalsIgnoreCase("false")) {
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
								channel.sendMessage("Description for new Text-Channels set to `" + message + "`.").queue();
							}
						}
					}
				} else if (message.startsWith("broadcast ") && sender.getId().equals("553576678186680340")) {
					message = message.substring(10);
					for (Guild g : jda.getGuilds()) {
						String prefix = "a!";
						try (ResultSet conf = getGuildConfig(g)) {
							prefix = conf.getString("Prefix").replace("<@!655496558095237130>", "@Argus ");
						} catch (SQLException e) {
							logger.log("ERROR", g.getName() + "(" + g.getId() + ") Config couldn't be loaded!");
							logger.logStackTrace(e);
						}
						final String messagef = message;
						final String prefixf = prefix;
						guild.retrieveOwner().queue(owner -> {
							final String mes = messagef.replace("[OWNER]", owner.getAsMention()).replace("[PREFIX]", prefixf);
							try {
								g.createTextChannel("argus").addPermissionOverride(g.getSelfMember(), selfPerms, null).addPermissionOverride(g.getPublicRole(), null, readPerms).queue(tc -> {
									tc.sendMessage(mes).queue(m -> {
										tc.sendMessage("You can delete this channel when you're done!").queue();
									});
								});
							} catch (InsufficientPermissionException e) {
								TextChannel tc = null;
								for (TextChannel c : guild.getTextChannels()) {
									if (guild.getSelfMember().hasPermission(c, Permission.MESSAGE_WRITE)) {
										tc = c;
										break;
									}
								}
								if (tc != null) { 
									tc.sendMessage(mes).queue();
								}
							}
						});
					}
					channel.sendMessage("Broadcast sent!").queue();
				}
			}
		} catch (SQLException e) {
			logger.log("ERROR", guild.getName() + "(" + guild.getId() + ") Config couldn't be loaded!");
			logger.logStackTrace(e);
		}
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

	public static String getAssociation(String vcID) throws SQLException {
		ResultSet association = sql.executeQuery("SELECT * FROM `Associations` WHERE `vc` = " + vcID);
		association.next();
		String returns = association.getString("tc");
		association.close();
		return returns;
	}

	public static void setAssociation(String vcID, String tcID) {
		try {
			getAssociation(vcID);
			removeAssociation(vcID);
		} catch (SQLException e) {
		} finally {
			sql.executeUpdate("INSERT INTO `Associations` (`vc`, `tc`) VALUES ('" + vcID + "', '" + tcID + "')");
		}
	}

	public static void removeAssociation(String vcID) {
		sql.executeUpdate("DELETE FROM `Associations` WHERE `vc` = " + vcID);
	}
}
