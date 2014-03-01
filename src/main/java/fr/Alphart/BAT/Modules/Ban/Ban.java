package fr.Alphart.BAT.Modules.Ban;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import fr.Alphart.BAT.BAT;
import fr.Alphart.BAT.Modules.BATCommand;
import fr.Alphart.BAT.Modules.IModule;
import fr.Alphart.BAT.Modules.ModuleConfiguration;
import fr.Alphart.BAT.Modules.Core.Core;
import fr.Alphart.BAT.Utils.FormatUtils;
import fr.Alphart.BAT.Utils.Utils;
import fr.Alphart.BAT.database.DataSourceHandler;
import fr.Alphart.BAT.database.SQLQueries;

public class Ban implements IModule, Listener{
	//TODO: Maybe cache the ban data when a player enter in a server
	private final String name = "ban";
	public static final String BAN_PERM = "BAT.ban";
	private ScheduledTask task;
	private BanCommand commandHandler;
	private BanConfig config;
	// Message
	private static final String BAN_MSG = "&a%entity%&e a ete &6banni definitivement&e par &a%staff%&e du serveur &a%serv%&e. Raison : %reason%";
	private static final String BANTEMP_MSG = "&a%entity%&e a ete &6banni &ependant &a%duration%&e par &a%staff%&e du serveur &a%serv%&e. Raison : %reason%";

	private static final String UNBAN_MSG = "&a%entity%&e a ete &6debanni&e par &a%staff%&e du serveur &a%serv%&e. Raison : %reason%";


	@Override
	public List<BATCommand> getCommands() {
		return commandHandler.getCmds();
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getMainCommand() {
		return "ban";
	}

	@Override
	public ModuleConfiguration getConfig() {
		return config;
	}	

	@Override
	public boolean load(){
		// Init table 
		try (Connection conn  = BAT.getConnection()) {
			final Statement statement = conn.createStatement();
			if(DataSourceHandler.isSQLite()){
				for(final String query : SQLQueries.Ban.SQLite.createTable){
					statement.executeUpdate(query);
				}
			}
			else{
				statement.executeUpdate(SQLQueries.Ban.createTable);
			}
			statement.close();
		} catch (final SQLException e) {
			DataSourceHandler.handleException(e);
		}

		// Load config
		config = new BanConfig(this);

		// Register commands
		commandHandler = new BanCommand(this);
		commandHandler.loadCmds();

		// Launch tempban task
		final BanTask banTask = new BanTask();
		task = ProxyServer.getInstance().getScheduler().schedule(BAT.getInstance(), banTask, 10, 10, TimeUnit.SECONDS);

		return true;
	}

	@Override
	public boolean unload() {
		task.cancel();
		return false;
	}
	public class BanConfig extends ModuleConfiguration{
		public BanConfig(final IModule module) {
			super(module);
		}
	}
	/**
	 * Check if both ip and name of this player are banned
	 * @param player
	 * @param server
	 * @return true if name or ip is banned
	 */
	public static boolean isBan(final ProxiedPlayer player, final String server){
		if(isBan(player.getName(), server)) {
			return true;
		}
		return false;
	}

	/**
	 * Check if this entity (player or ip) is banned
	 * @param bannedEntity | can be an ip or a player name
	 * @param server | if server equals to (any) check if the player is ban on a server
	 * @return
	 */
	public static boolean isBan(final String bannedEntity, final String server){
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try (Connection conn  = BAT.getConnection()) {
			// If this is an ip which may be banned
			if(Utils.validIP(bannedEntity)){
				final String ip = bannedEntity;
				statement = conn.prepareStatement((ANY_SERVER.equals(server)) ? SQLQueries.Ban.isBanIP : SQLQueries.Ban.isBanServerIP);
				statement.setString(1, ip);
				if(!ANY_SERVER.equals(server)) {
					statement.setString(2, server);
				}
			}
			// If this is a player which may be banned
			else{
				final String pName = bannedEntity;
				final String ip = Core.getPlayerIP(pName);
				statement = conn.prepareStatement((ANY_SERVER.equals(server)) ? SQLQueries.Ban.isBan : SQLQueries.Ban.isBanServer);
				statement.setString(1, pName);
				statement.setString(2, ip);
				if(!ANY_SERVER.equals(server)) {
					statement.setString(3, server);
				}
			}
			resultSet = statement.executeQuery();

			// If there are a result
			if(resultSet.next()) {
				return true;
			}

		} catch (final SQLException e) {
			DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement, resultSet);
		}
		return false;
	}

	/**
	 * Ban this entity (player or ip) <br>
	 * @param bannedEntity | can be an ip or a player name
	 * @param server ; set to "(global)", to global ban
	 * @param staff
	 * @param duration ; set to 0 for ban def
	 * @param reason | optional
	 * @return 
	 */
	public static String ban(final String bannedEntity, final String server, final String staff, final Integer duration, final String reason){
		try (Connection conn  = BAT.getConnection()) {
			// If the bannedEntity is an ip
			if(Utils.validIP(bannedEntity)){
				final String ip = bannedEntity;

				final PreparedStatement statement = conn.prepareStatement(SQLQueries.Ban.createBanIP);
				statement.setString(1, ip);
				statement.setString(2, staff);
				statement.setString(3, server);
				statement.setTimestamp(4, (duration > 0) ? new Timestamp(System.currentTimeMillis() + duration * 1000) : null);
				statement.setString(5, (NO_REASON.equals(reason)) ? null : reason);
				statement.executeUpdate();
				statement.close();

				for(final ProxiedPlayer player : ProxyServer.getInstance().getPlayers()){
					if(Utils.getPlayerIP(player).equals(ip)){
						BAT.kick(player, "Vous avez ete banni. Raison : " + ( ((NO_REASON.equals(reason)) ? "non specifié" : reason) ));
					}
				}

				return FormatUtils.formatBroadcastMsg((duration > 0) ? BANTEMP_MSG : BAN_MSG, ip, staff, server, reason, (duration > 0) ? FormatUtils.secToDate(duration) : "");
			}

			// Otherwise it's a player
			else{
				final String pName = bannedEntity;
				final ProxiedPlayer player = ProxyServer.getInstance().getPlayer(pName);
				final String ip = Core.getPlayerIP(pName);
				final PreparedStatement statement = conn.prepareStatement(SQLQueries.Ban.createBan);
				statement.setString(1, pName);
				statement.setString(2, ip);
				statement.setString(3, staff);
				statement.setString(4, server);
				statement.setTimestamp(5, (duration > 0) ? new Timestamp(System.currentTimeMillis() + duration * 1000) : null);
				statement.setString(6, (NO_REASON.equals(reason)) ? null : reason);
				statement.executeUpdate();
				statement.close();

				// Kick player if he's online and on the server where he's banned
				if(player != null && (server.equals(GLOBAL_SERVER) || player.getServer().getInfo().getName().equals(server))){
					BAT.kick(player, "Vous avez ete banni. Raison : " + ( ((NO_REASON.equals(reason)) ? "non specifié" : reason) ));
				}

				return FormatUtils.formatBroadcastMsg((duration > 0) ? BANTEMP_MSG : BAN_MSG, pName, staff, server, reason, (duration > 0) ? FormatUtils.secToDate(duration) : "");		
			}
		} catch (final SQLException e) {
			return DataSourceHandler.handleException(e);
		}
	}

	/**
	 * Ban the ip of an online player
	 * @param server ; set to "(global)", to global ban
	 * @param staff
	 * @param duration ; set to 0 for ban def
	 * @param reason | optional
	 * @param ip
	 */
	public static String banIP(final ProxiedPlayer player, final String server, final String staff, final Integer duration, final String reason){
		ban(Utils.getPlayerIP(player), server, staff, duration, reason);
		BAT.kick(player, "Vous avez ete banni. Raison : " + ( ((NO_REASON.equals(reason)) ? "non specifié" : reason) ));
		return FormatUtils.formatBroadcastMsg((duration > 0) ? BANTEMP_MSG : BAN_MSG, player.getName(), staff, server, reason, FormatUtils.secToDate(duration));
	}

	/**
	 * Unban an entity (player or ip)
	 * @param bannedEntity | can be an ip or a player name
	 * @param server | if equals to (any), unban from all servers | if equals to (global), remove global ban
	 * @param staff
	 * @param reason
	 * @param unBanIP
	 */
	public static String unBan(final String bannedEntity, final String server,  final String staff, final String reason){
		PreparedStatement statement = null;
		try (Connection conn  = BAT.getConnection()) {
			// If the bannedEntity is an ip
			if(Utils.validIP(bannedEntity)){
				final String ip = bannedEntity;
				if(ANY_SERVER.equals(server)){
					statement = (DataSourceHandler.isSQLite()) 
							? conn.prepareStatement(SQLQueries.Ban.SQLite.unBanIP)
									: conn.prepareStatement(SQLQueries.Ban.unBanIP);
							statement.setString(1, reason);
							statement.setString(2, staff);
							statement.setString(3, ip);
				}
				else{
					statement = (DataSourceHandler.isSQLite()) 
							? conn.prepareStatement(SQLQueries.Ban.SQLite.unBanIPServer) 
									: conn.prepareStatement(SQLQueries.Ban.unBanIPServer);
							statement.setString(1, reason);
							statement.setString(2, staff);
							statement.setString(3, ip);
							statement.setString(4, server);
				}
				statement.executeUpdate();

				return FormatUtils.formatBroadcastMsg(UNBAN_MSG, ip, staff, server, reason, "");
			}

			// Otherwise it's a player
			else{
				final String pName = bannedEntity;
				if(ANY_SERVER.equals(server)){
					statement = (DataSourceHandler.isSQLite()) 
							? conn.prepareStatement(SQLQueries.Ban.SQLite.unBan)
									: conn.prepareStatement(SQLQueries.Ban.unBan);
							statement.setString(1, reason);
							statement.setString(2, staff);
							statement.setString(3, pName);
				}
				else{
					statement = (DataSourceHandler.isSQLite()) 
							? conn.prepareStatement(SQLQueries.Ban.SQLite.unBanServer)
									: conn.prepareStatement(SQLQueries.Ban.unBanServer);
							statement.setString(1, reason);
							statement.setString(2, staff);
							statement.setString(3, pName);
							statement.setString(4, server);
				}
				statement.executeUpdate();

				return FormatUtils.formatBroadcastMsg(UNBAN_MSG, pName, staff, server, reason, "");
			}
		} catch (final SQLException e) {
			return DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement);
		}

	}

	/**
	 * Unban the ip of this entity
	 * @param entity
	 * @param server | if equals to (any), unban from all servers | if equals to (global), remove global ban
	 * @param staff
	 * @param reason | optional
	 * @param duration ; set to 0 for ban def
	 */
	public static String unBanIP(final String entity, final String server, final String staff, final String reason){
		Ban.unBan((Utils.validIP(entity)) ? entity : Core.getPlayerIP(entity), server, staff, reason);
		return FormatUtils.formatBroadcastMsg(UNBAN_MSG, entity, staff, server, reason, "");
	}

	/**
	 * Get all ban data of an entity <br>
	 * <b>Should be runned async to optimize performance</b>
	 * @param entity
	 * @return List of BanEntry of the player
	 */
	public List<BanEntry> getBanData(final String entity){
		final List<BanEntry> banList = new ArrayList<BanEntry>();
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try  (Connection conn  = BAT.getConnection()) {
			// If the entity is an ip
			if(Utils.validIP(entity))
			{
				final String ip = entity;
				statement = conn.prepareStatement(SQLQueries.Ban.getBanIP);
				statement.setString(1, ip);
				resultSet = statement.executeQuery();

				while(resultSet.next()){
					final String server = resultSet.getString("ban_server");
					final String reason = resultSet.getString("ban_reason");
					final String staff = resultSet.getString("ban_staff");
					final int begin_date = resultSet.getInt("ban_begin");
					final int end_date = resultSet.getInt("ban_end");
					final boolean active = resultSet.getBoolean("ban_state");
					banList.add( new BanEntry(ip, server, reason, staff, begin_date, end_date, active) );
				}	
			}

			// Otherwise if it's a player
			else
			{
				final String pName = entity;
				statement = conn.prepareStatement(SQLQueries.Ban.getBan);
				statement.setString(1, pName);
				statement.setString(2, Core.getPlayerIP(pName));
				resultSet = statement.executeQuery();

				while(resultSet.next()){
					final String server = resultSet.getString("ban_server");
					final String reason = resultSet.getString("ban_reason");
					final String staff = resultSet.getString("ban_staff");
					final int begin_date = resultSet.getInt("ban_begin");
					final int end_date = resultSet.getInt("ban_end");
					final boolean active = (resultSet.getBoolean("ban_state") ? true : false);
					banList.add( new BanEntry(pName, server, reason, staff, begin_date, end_date, active) );
				}	
			}
		} catch (final SQLException e) {
			DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement, resultSet);
		}
		return banList;
	}

	// Event listener
	/**
	 * Used to store the player to kick in the ServerConnectedEvent
	 */
	private final List<String> playerToKick = new ArrayList<String>();
	@EventHandler
	public void onServerConnect(final ServerConnectEvent e) {
		final ProxiedPlayer player = e.getPlayer();

		if(Ban.isBan(player, e.getTarget().getName())){
			if(e.getTarget().getName().equals(player.getPendingConnection().getListener().getDefaultServer())){
				// We need to kick the player during the ServerConnectedEvent
				playerToKick.add(player.getName());
				return;
			}
			player.sendMessage(BAT.__("Vous etes banni de ce serveur."));
			if(player.getServer() == null){
				player.connect(ProxyServer.getInstance().getServerInfo(player.getPendingConnection().getListener().getDefaultServer()));
			}
			e.setCancelled(true);
		}
	}

	// Use in order to the player get the right ban message
	@EventHandler
	public void onServerConnected(final ServerConnectedEvent e){
		if(playerToKick.remove(e.getPlayer().getName())) {
			e.getPlayer().disconnect(BAT.__("Vous etes banni !"));
		}
	}  
	@EventHandler
	public void onPlayerLogin(final LoginEvent e){
		e.registerIntent(BAT.getInstance());
		BAT.getInstance().getProxy().getScheduler().runAsync(BAT.getInstance(), new Runnable() {
			@Override
			public void run() {
				try {
					final String pName = e.getConnection().getName();
					if(Ban.isBan(pName, "(global)")){
						e.setCancelled(true);
						e.setCancelReason("Vous etes banni !");
					}

				} finally {
					e.completeIntent(BAT.getInstance());
				}
			}
		});
	}
}