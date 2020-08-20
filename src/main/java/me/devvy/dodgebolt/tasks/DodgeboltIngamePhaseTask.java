package me.devvy.dodgebolt.tasks;

import me.devvy.dodgebolt.game.DodgeboltGame;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class DodgeboltIngamePhaseTask extends DodgeboltPhaseTask {

    public static final int PERIOD = 20;

    public DodgeboltIngamePhaseTask(DodgeboltGame game) {
        super(game);
    }

    @Override
    protected void runGameLoop() {

        if (unpausedElapsed > 0 && unpausedElapsed % 15 == 0)
            game.getArena().shrinkArena();

        for (Player player : Bukkit.getOnlinePlayers())
            player.sendActionBar(game.getBothTeamAliveCountString());
    }

    @Override
    protected void runPauseLoop() {

    }


}
