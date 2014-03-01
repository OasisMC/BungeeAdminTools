package fr.Alphart.BAT.Modules.Kick;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import fr.Alphart.BAT.BAT;
import fr.Alphart.BAT.Modules.BATCommand;
import fr.Alphart.BAT.Modules.IModule;
import fr.Alphart.BAT.Modules.ModuleConfiguration;
import fr.Alphart.BAT.Utils.FormatUtils;
import fr.Alphart.BAT.Utils.Utils;
import fr.Alphart.BAT.database.DataSourceHandler;
import fr.Alphart.BAT.database.SQLQueries;

public class Kick implements IModule{
	private final String name = "kick";
	public static final String KICK_PERM = "BAT.kick";
	private KickCommand commandHandler;
	private KickConfig config;

	// Message
	private final static String KICK_MSG = "&a%entity%&e a ete &6kicke&e par &a%staff%&e du serveur &a%serv%&e. Raison : %reason%";
	private final static String GKICK_MSG = "&a%entity%&e a ete &6kicke&e par &a%staff%&e du proxy. Raison : %reason%";

	@Override
	public List<BATCommand> getCommands() {return commandHandler.getCmds();}

	@Override
	public String getMainCommand() {
		return "kick";
	}
	@Override
	public String getName() {return name;}

	@Override
	public ModuleConfiguration getConfig() {
		return config;
	}

	@Override
	public boolean load() {
		// Init table
		Statement statement = null;
		try  (Connection conn  = BAT.getConnection()) {
			statement = conn.createStatement();
			if(DataSourceHandler.isSQLite()){
				for(final String query : SQLQueries.Kick.SQLite.createTable){
					statement.executeUpdate(query);
				}
			}
			else{
				statement.executeUpdate(SQLQueries.Kick.createTable);
			}
			statement.close();
		} catch (final SQLException e) {
			DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement);
		}

		// Config initialize
		config = new KickConfig(this);

		// Register commands
		commandHandler = new KickCommand(this);
		commandHandler.loadCmds();

		return true;
	}

	@Override
	public boolean unload() {

		return false;
	}
	public class KickConfig extends ModuleConfiguration{
		public KickConfig(final IModule module) {
			super(module);
		}
	}


	/**
	 * Kick a player and tp him to the default server
	 * @param player
	 * @param reason
	 */
	public static String kick(final ProxiedPlayer player, final String staff, final String reason){
		PreparedStatement statement = null;
		try  (Connection conn = BAT.getConnection()) {			
			final String server = player.getServer().getInfo().getName();
			if(DataSourceHandler.isSQLite()){
				statement = conn.prepareStatement(SQLQueries.Kick.SQLite.kickPlayer);
			}else{
				statement = conn.prepareStatement(SQLQueries.Kick.kickPlayer);
			}
			statement.setString(1, player.getName());
			statement.setString(2, Utils.getPlayerIP(player));
			statement.setString(3, staff);
			statement.setString(4, reason);
			statement.setString(5, server);
			statement.executeUpdate();
			statement.close();
			player.connect( ProxyServer.getInstance().getServerInfo(player.getPendingConnection().getListener().getDefaultServer()) );
			player.sendMessage(BAT.__("Vous avez ete expulse de ce serveur ! Raison : " +  ((reason != null) ? reason : "Non specifie") ));

			return FormatUtils.formatBroadcastMsg(KICK_MSG, player.getName(), staff, server, reason, "");
		} catch (final SQLException e) {
			return DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement);
		}
	}

	/**
	 * Kick a player from the network
	 * @param player
	 * @param reason
	 */
	public static String gKick(final ProxiedPlayer player, final String staff, final String reason){
		PreparedStatement statement = null;
		try  (Connection conn  = BAT.getConnection()) {	
			if(DataSourceHandler.isSQLite()){
				statement = conn.prepareStatement(fr.Alphart.BAT.database.SQLQueries.Kick.SQLite.kickPlayer);
			}else{
				statement = conn.prepareStatement(SQLQueries.Kick.kickPlayer);
			}
			statement.setString(1, player.getName());
			statement.setString(2, Utils.getPlayerIP(player));
			statement.setString(3, staff);
			statement.setString(4, reason);
			statement.setString(5, "(global)");
			statement.executeUpdate();
			statement.close();
			player.disconnect(TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', "Vous avez ete expulse de ce serveur ! Raison : " + ((reason != null) ? reason : "Non specifie")) ));

			return FormatUtils.formatBroadcastMsg(GKICK_MSG, player.getName(), staff, GLOBAL_SERVER, reason, "");
		} catch (final SQLException e) {
			return DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement);
		}
	}

	/**
	 * Get all kick data of a player <br>
	 * <b>Should be runned async to optimize performance</b>
	 * @param player's name
	 * @return List of KickEntry of the player
	 */
	public List<KickEntry> getKickData(final String pName){
		final List<KickEntry> kickList = new ArrayList<KickEntry>();
		PreparedStatement statement = null;
		ResultSet resultSet = null;
		try  (Connection conn  = BAT.getConnection()) {	
			statement = conn.prepareStatement(SQLQueries.Kick.getKick);
			statement.setString(1, pName);
			resultSet = statement.executeQuery();

			while(resultSet.next()){
				final String server = resultSet.getString("kick_server");
				final String reason = resultSet.getString("kick_reason");
				final String staff = resultSet.getString("kick_staff");
				final int date = resultSet.getInt("kick_date");
				kickList.add( new KickEntry(pName, server, reason, staff, date) );
			}
		} catch (final SQLException e) {
			DataSourceHandler.handleException(e);
		} finally{
			DataSourceHandler.close(statement, resultSet);
		}
		return kickList;
	}
}