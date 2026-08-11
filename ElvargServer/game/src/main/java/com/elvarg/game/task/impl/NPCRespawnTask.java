package com.elvarg.game.task.impl;

import com.elvarg.game.World;
import com.elvarg.game.entity.impl.npc.NPC;
import com.elvarg.game.task.Task;

/**
 * A {@link Task} implementation which handles the respawn of an npc.
 *
 * @author Professor Oak
 */
public class NPCRespawnTask extends Task {

    /**
     * The {@link NPC} which is going to respawn.
     */
    private final NPC npc;

    public NPCRespawnTask(NPC npc, int ticks) {
        super(ticks);
        this.npc = npc;
        // NPC-RESPAWN BUG FIX SCOPE NOTE (com.elvarg.rl, PROJECT_STATE.md section 13): re-binds this
        // task's key from Task's shared DEFAULT_KEY to the specific NPC instance being respawned.
        // Every other respawn in the game still fires on exactly the same schedule as before -- this
        // ONLY makes each respawn INDIVIDUALLY cancellable via TaskManager.cancelTasks(npc), which
        // nothing currently does or needs; it was previously impossible to cancel a single respawn
        // without also cancelling every other DEFAULT_KEY task in the game. Global respawn behavior
        // is otherwise unchanged -- this is the one stock-file touch this fix needs, matching the
        // HitQueue.clear() precedent (added, not used anywhere else in stock engine logic).
        this.bind(npc);
    }

    @Override
    public void execute() {
        // Register the new entity..
        World.getAddNPCQueue().add(npc.clone());

        // Stop the task
        stop();
    }
}
