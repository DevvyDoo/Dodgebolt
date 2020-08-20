package me.devvy.dodgebolt.game;

import me.devvy.dodgebolt.Dodgebolt;
import me.devvy.dodgebolt.events.TeamColorChangeEvent;
import me.devvy.dodgebolt.tasks.DodgeboltIngamePhaseTask;
import me.devvy.dodgebolt.tasks.DodgeboltIntermissionPhaseTask;
import me.devvy.dodgebolt.tasks.DodgeboltPhaseTask;
import me.devvy.dodgebolt.tasks.DodgeboltPregamePhaseTask;
import me.devvy.dodgebolt.team.Team;
import me.devvy.dodgebolt.util.Fireworks;
import me.devvy.dodgebolt.util.Items;
import me.devvy.dodgebolt.util.Phrases;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.*;

public class DodgeboltGame implements Listener {

    private final DodgeboltArena arena;
    private final Team team1;
    private final Team team2;
    private DodgeboltPhaseTask currentPhaseTask = null;
    private DodgeboltGameState state;

    private DodgeboltArrowSpawners arrowSpawners;

    private final MinecraftScoreboardManager scoreboardManager;

    public DodgeboltGame() {
        World arenaWorld = Bukkit.getWorld("world");
        if (arenaWorld == null)
            throw new IllegalStateException("There must be a world named 'world'!");

        arenaWorld.setGameRule(GameRule.DO_FIRE_TICK, false);  // Important so lava doesn't destroy arena

        arena = new DodgeboltArena(new Location(arenaWorld, 50, 100, 50));
        arena.generateArena();

        team1 = new Team(ChatColor.BLUE);
        team2 = new Team(ChatColor.LIGHT_PURPLE);

        arena.changeTeamCarpetColors(team1.getTeamColor(), team2.getTeamColor());

        state = DodgeboltGameState.WAITING;

        new TeamSwitchSign(this, arena.getSpawn().clone().add(-5, 0, 1), BlockFace.EAST, team1);
        new TeamSwitchSign(this, arena.getSpawn().clone().add(-5, 0, -1),BlockFace.EAST, team2);
        new SpectatorSwitchSign(this, arena.getSpawn().clone().add( -5, 0, 0), BlockFace.EAST);
        new StartGameSign(this, arena.getSpawn().clone().add(-5, 1, 0), BlockFace.EAST);

        scoreboardManager = new MinecraftScoreboardManager(this);
        Dodgebolt.getPlugin(Dodgebolt.class).getServer().getPluginManager().registerEvents(scoreboardManager, Dodgebolt.getPlugin(Dodgebolt.class));

        for (Player player : Bukkit.getOnlinePlayers()) {

            if (player.isDead())
                player.spigot().respawn();

            player.setInvulnerable(true);
            player.setAllowFlight(true);
            player.teleport(arena.getSpawn());
            player.setGameMode(GameMode.SURVIVAL);
        }
    }

    public DodgeboltArena getArena() {
        return arena;
    }

    public Team getTeam1() {
        return team1;
    }

    public Team getTeam2() {
        return team2;
    }

    public Team getOpposingTeam(Team team) {

        if (team == team1)
            return team2;
        else if (team == team2)
            return team1;
        return null;

    }

    public Team getPlayerTeam(Player player) {
        if (team1.isMember(player))
            return team1;
        else if (team2.isMember(player))
            return team2;
        return null;
    }

    public void setPlayerTeam(Player player, Team team) {

        if (getPlayerTeam(player) != null)
            getPlayerTeam(player).removePlayer(player);

        team.addPlayer(player);
    }

    public void setSpectating(Player player) {
        getTeam2().removePlayer(player);
        getTeam1().removePlayer(player);
        player.setDisplayName(ChatColor.DARK_GRAY + "[SPEC] " + ChatColor.stripColor(player.getName()));
    }

    public DodgeboltGameState getState() {
        return state;
    }

    public void setState(DodgeboltGameState state) {
        this.state = state;
    }

    public boolean isInProgress() {
        return DodgeboltGameState.isGameRunning(state);
    }

    public String getBothTeamAliveCountString() {
        return getTeam1().getTeamColor() + "" + ChatColor.BOLD + getTeam1().getElimTracker().getTeamMembersAlive() + ChatColor.GRAY + " v " + getTeam2().getTeamColor() + "" + ChatColor.BOLD + getTeam2().getElimTracker().getTeamMembersAlive();
    }

    /**
     * Called when we want to start a brand new fresh game
     */
    public void startNewGame() {

        if (getState() != DodgeboltGameState.WAITING)
            return;

        // Reset the score
        getTeam1().setScore(0);
        getTeam2().setScore(0);

        startNewRound();
    }

    public void givePlayerKit(Player player) {

        Team team = getPlayerTeam(player);
        if (team == null)
            return;

        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
        LeatherArmorMeta bootMeta = (LeatherArmorMeta) boots.getItemMeta();
        bootMeta.setColor(Fireworks.translateChatColorToColor(team.getTeamColor()));
        bootMeta.setUnbreakable(true);
        bootMeta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        bootMeta.setDisplayName(team.getTeamColor() + team.getName() + " Boots");
        boots.setItemMeta(bootMeta);

        player.getInventory().setBoots(boots);
        player.getInventory().addItem(Items.getDodgeboltBow());
    }

    /**
     * Called when we should start a fresh round, not an entire game
     */
    public void startNewRound() {

        setState(DodgeboltGameState.PREGAME_COUNTDOWN);

        for (Entity entity : arena.getOrigin().getWorld().getEntities())
            if (entity instanceof Item || entity instanceof Arrow)
                entity.remove();


        // Fix the arena
        arena.restoreArena();

        // Enable barriers
        arena.enableBarriers();

        // Tp the players to their spots
        for (Team team : new Team[]{team1, team2}) {

            int i = 0;
            for (Player player : team.getMembersAsPlayers()) {
                Location spawn = arena.getSpawnLocation(i, team == team2);

                if (player.isDead())
                    player.spigot().respawn();

                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(false);
                player.setInvulnerable(false);
                player.getInventory().clear();
                player.teleport(spawn);
                i++;
                player.setGlowing(true);
                givePlayerKit(player);
            }
        }

        currentPhaseTask = new DodgeboltPregamePhaseTask(this);
        currentPhaseTask.runTaskTimer(Dodgebolt.getPlugin(Dodgebolt.class), 0, DodgeboltPregamePhaseTask.PERIOD);
    }

    public void exitPregamePhase() {
        currentPhaseTask.cancel();
        currentPhaseTask = new DodgeboltIngamePhaseTask(this);
        currentPhaseTask.runTaskTimer(Dodgebolt.getPlugin(Dodgebolt.class), 0, DodgeboltIngamePhaseTask.PERIOD);

        team1.getElimTracker().reset(team1.getMembersAsPlayers().size());
        team2.getElimTracker().reset(team2.getMembersAsPlayers().size());

        setState(DodgeboltGameState.INGAME);

        arena.disableBarriers();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_DESTROY, 1, 1.5f);
            player.sendTitle("", ChatColor.RED + "Eliminate" + ChatColor.GRAY + " the other team!", 1, 30, 5);

            player.setFireTicks(0);
        }

        arrowSpawners = new DodgeboltArrowSpawners(this, Arrays.asList(arena.getArrowSpawnLocations()));
    }

    public void exitGameRoundPhase(Team winner) {
        currentPhaseTask.cancel();
        currentPhaseTask = new DodgeboltIntermissionPhaseTask(this);
        currentPhaseTask.runTaskTimer(Dodgebolt.getPlugin(Dodgebolt.class), 1, DodgeboltIntermissionPhaseTask.PERIOD);

        setState(DodgeboltGameState.INTERMISSION);
        winner.setScore(winner.getScore() + 1);
        Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.YELLOW + "!" + ChatColor.GRAY + "] " + winner.getTeamColor() + ChatColor.BOLD.toString() + winner.getName() + ChatColor.GREEN + " won the round!");
        for (Player player : winner.getMembersAsPlayers()) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, .75f, 1);
            Fireworks.spawnVictoryFireworks(player, Fireworks.translateChatColorToColor(winner.getTeamColor()));
        }

        for (Player player : getOpposingTeam(winner).getMembersAsPlayers())
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1, 1);

        for (Player player : Bukkit.getOnlinePlayers()) {

            String bigTitle = "";
            Team playersTeam = getPlayerTeam(player);
            if (playersTeam == winner)
                bigTitle = ChatColor.GREEN + "Round Win!";
            else if (playersTeam == getOpposingTeam(winner))
                bigTitle = ChatColor.RED + "Round Lost!";

            player.sendTitle(bigTitle, winner.getTeamColor() + ChatColor.BOLD.toString() + winner.getName() + ChatColor.GRAY + " won the round!", 10, 60, 20);

            player.getInventory().clear();
            player.setGlowing(false);
            player.setInvulnerable(true);
            player.setAllowFlight(true);
        }

        arrowSpawners.delete();
        arrowSpawners = null;
    }

    public void exitIntermissionPhase() {
        currentPhaseTask.cancel();
        currentPhaseTask = null;
        startNewRound();
    }

    public void endGame() {

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setGameMode(GameMode.SURVIVAL);
            player.setInvulnerable(true);
            player.setAllowFlight(true);
        }

    }

    public void cleanup() {
        arena.destroyArena();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        setSpectating(event.getPlayer());
        event.getPlayer().teleport(arena.getSpawn());
        event.getPlayer().setGameMode(state == DodgeboltGameState.WAITING ? GameMode.SURVIVAL : GameMode.ADVENTURE);
        event.getPlayer().setAllowFlight(true);
    }

    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event) {

        if (state == DodgeboltGameState.INGAME) {
            event.getPlayer().setHealth(0);
            handleIngameDeath(event.getPlayer(), true);
        }

        team1.removePlayer(event.getPlayer());
        team2.removePlayer(event.getPlayer());

        event.getPlayer().teleport(arena.getSpawn());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        event.setRespawnLocation(arena.getSpawn());
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {

        for (ItemStack item : event.getEntity().getInventory())
            if (item != null && item.getType() != Material.ARROW)
                item.setAmount(0);

        event.setDeathMessage("");
        Team playerTeam = getPlayerTeam(event.getEntity());
        if (playerTeam == null || state != DodgeboltGameState.INGAME)
            return;

        handleIngameDeath(event.getEntity(), false);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        event.setFormat("%s " + ChatColor.WHITE + ">> " + ChatColor.GRAY + "%s");
    }

    @EventHandler
    public void onPlayerFellInVoid(PlayerMoveEvent event) {
        if (event.getTo().getY() < 0)
            event.getPlayer().teleport(arena.getSpawn());
    }

    public boolean outOfBowRange(Location location) {
        int offset = Math.abs(arena.getOrigin().getBlockX() - location.getBlockX()) > 5 ? 0 : 1;
        return Math.abs(arena.getOrigin().getBlockZ() - location.getBlockZ()) < 3 + offset;
    }

    public void handleIngameDeath(Player player, boolean wasQuit) {
        Team team = getPlayerTeam(player);
        if (team == null || state != DodgeboltGameState.INGAME)
            return;

        boolean newDeath = team.getElimTracker().teamMemberDied(player);
        if (!newDeath)
            return;

        Player killer = player.getKiller();

        for (Player otherPlayers : Bukkit.getOnlinePlayers()) {

            if (otherPlayers == killer)
                otherPlayers.sendTitle(ChatColor.GRAY + "[" + ChatColor.RED + "✘" + ChatColor.GRAY + "] " + player.getDisplayName(), getBothTeamAliveCountString(), 5, 15, 5);
            else
                otherPlayers.sendTitle("", getBothTeamAliveCountString(), 5, 15, 5);

            Team otherPlayersTeam = getPlayerTeam(otherPlayers);
            if (otherPlayersTeam == null || getOpposingTeam(otherPlayersTeam) == team)
                otherPlayers.playSound(otherPlayers.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
            else
                otherPlayers.playSound(otherPlayers.getLocation(), Sound.ENTITY_ENDERMAN_HURT, 1, .8f);

        }

        player.setAllowFlight(true);

        if (wasQuit)
            Bukkit.broadcastMessage(Phrases.getRandomSuicidePhrase(player));
        else
            Bukkit.broadcastMessage(ChatColor.GRAY + "[" + ChatColor.RED + "✘" + ChatColor.GRAY + "] " + (killer != null ? Phrases.getRandomKilledPhrase(player, killer) : Phrases.getRandomSuicidePhrase(player)));

        if (team.getElimTracker().teamIsDead())
            exitGameRoundPhase(getOpposingTeam(team));
        else
            arena.shrinkArena();

        Fireworks.spawnFireworksInstantly(player.getLocation(), Fireworks.translateChatColorToColor(team.getTeamColor()));
    }

    @EventHandler
    public void onPlayerMoveDuringGame(PlayerMoveEvent event) {

        if (state != DodgeboltGameState.INGAME)
            return;

        if (getPlayerTeam(event.getPlayer()) == null)
            return;

        if (event.getTo().getBlockZ() == arena.getOrigin().getBlockZ()) {
            if (event.getTo().getBlockY() < arena.getOrigin().getBlockY() + 4) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Stay on your side!");
            }
        }

        if (event.getFrom().getBlock() != event.getTo().getBlock() && outOfBowRange(event.getTo()) && !outOfBowRange(event.getFrom())) {
            for (ItemStack item : event.getPlayer().getInventory())
                if (item != null && item.getType() == Material.BOW)
                    item.setAmount(0);
        }
        else if (event.getFrom().getBlock() != event.getTo().getBlock() && outOfBowRange(event.getFrom()) && !outOfBowRange(event.getTo()))
            event.getPlayer().getInventory().addItem(Items.getDodgeboltBow());
    }

    @EventHandler
    public void onPlayerTookLavaDamage(EntityDamageEvent event) {

        if (state == DodgeboltGameState.INTERMISSION || state == DodgeboltGameState.PREGAME_COUNTDOWN || event.getCause() == EntityDamageEvent.DamageCause.FALL)
            event.setCancelled(true);

        else if (state == DodgeboltGameState.INGAME && event.getCause() == EntityDamageEvent.DamageCause.LAVA)
            event.setDamage(10000);
    }

    @EventHandler
    public void onPlayerPVP(EntityDamageByEntityEvent event) {

        if (state != DodgeboltGameState.INGAME)
            return;

        event.setCancelled(true);

        if (!(event.getEntity() instanceof Player))
            return;

        if (!(event.getDamager() instanceof Arrow))
            return;

        if (event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE)
            return;

        event.setCancelled(false);
        event.setDamage(10000);
    }

    @EventHandler
    public void onBowShoot(EntityShootBowEvent event) {

        if (state != DodgeboltGameState.INGAME)
            return;

        if (!(event.getEntity() instanceof Player))
            return;

        Team playersTeam = getPlayerTeam((Player) event.getEntity());
        if (playersTeam == null)
            return;

        if (event.getProjectile() instanceof Arrow) {
            ((Arrow) event.getProjectile()).setColor(Fireworks.translateChatColorToColor(playersTeam.getTeamColor()));
            event.getProjectile().setGlowing(true);
        }

        if (outOfBowRange(event.getEntity().getLocation()))
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeamColorChange(TeamColorChangeEvent event) {

        ChatColor team1New = team1.getTeamColor();
        ChatColor team2New = team2.getTeamColor();

        if (event.getTeam() == team1)
            team1New = event.getNew();
        else if (event.getTeam() == team2)
            team2New = event.getNew();

        arena.changeTeamCarpetColors(team1New, team2New);
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.getPlayer().isOp())
            event.setCancelled(true);
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        if (state == DodgeboltGameState.INGAME && event.getEntity().getItemStack().getType() == Material.ARROW)
            event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isInProgress() && event.getItemDrop().getItemStack().getType() != Material.ARROW)
            event.setCancelled(true);
        else if (state == DodgeboltGameState.INGAME)
            event.getItemDrop().setGlowing(true);
    }

    @EventHandler
    public void onPlayerPostDeath(ItemSpawnEvent event) {
        if (state == DodgeboltGameState.INGAME && event.getEntity().getItemStack().getType() == Material.ARROW)
            event.getEntity().setGlowing(true);
    }

    @EventHandler
    public void onClickedArmor(InventoryClickEvent event) {
        if (isInProgress() && event.getSlotType() == InventoryType.SlotType.ARMOR)
            event.setCancelled(true);
    }




}