package think.rpgitems.power.impl;

import cat.nyaa.nyaacore.Pair;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import think.rpgitems.RPGItems;
import think.rpgitems.power.*;
import think.rpgitems.power.marker.Selector;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static think.rpgitems.power.Utils.checkCooldown;

@Meta(defaultTrigger = "RIGHT_CLICK", generalInterface = PowerPlain.class, implClass = Scoreboard.Impl.class)
public class Scoreboard extends BasePower {

    private static LoadingCache<String, Pair<Set<String>, Set<String>>> teamCache = CacheBuilder
                                                                                            .newBuilder()
                                                                                            .concurrencyLevel(1)
                                                                                            .expireAfterAccess(1, TimeUnit.DAYS)
                                                                                            .build(CacheLoader.from(Selector::parse));
    private static LoadingCache<String, Pair<Set<String>, Set<String>>> tagCache = CacheBuilder
                                                                                           .newBuilder()
                                                                                           .concurrencyLevel(1)
                                                                                           .expireAfterAccess(1, TimeUnit.DAYS)
                                                                                           .build(CacheLoader.from(Selector::parse));
    @Property
    public String tag;
    @Property
    public String team;
    @Property
    public int cooldown = 0;
    @Property
    public ScoreboardOperation scoreOperation = ScoreboardOperation.NO_OP;
    @Property
    public int value = 0;
    @Property
    public String objective = "";
    @Property
    public int cost = 0;
    @Property
    public boolean reverseTagAfterDelay = false;
    @Property
    public long delay = 20;
    @Property
    public boolean abortOnSuccess = false;
    @Property
    public boolean requireHurtByEntity = true;
    private BukkitRunnable removeTask;

    /**
     * Cooldown time of this power
     */
    public int getCooldown() {
        return cooldown;
    }

    /**
     * Cost of this power
     */
    public int getCost() {
        return cost;
    }

    public long getDelay() {
        return delay;
    }

    @Override
    public String getName() {
        return "scoreboard";
    }

    @Override
    public String displayText() {
        return null;
    }

    public String getObjective() {
        return objective;
    }

    public ScoreboardOperation getScoreOperation() {
        return scoreOperation;
    }

    /**
     * Tag(s) to add and remove, according to the following format
     * `TO_ADD,!TO_REMOVE`
     */
    public String getTag() {
        return tag;
    }

    /**
     * Team(s) to join and leave, according to the following format
     * `JOIN,!LEAVE`
     */
    public String getTeam() {
        return team;
    }

    public int getValue() {
        return value;
    }

    public boolean isAbortOnSuccess() {
        return abortOnSuccess;
    }

    public boolean isRequireHurtByEntity() {
        return requireHurtByEntity;
    }

    public boolean isReverseTagAfterDelay() {
        return reverseTagAfterDelay;
    }

    public enum ScoreboardOperation {
        NO_OP, ADD_SCORE, SET_SCORE, RESET_SCORE
    }

    public class Impl implements PowerHit, PowerHitTaken, PowerHurt, PowerLeftClick, PowerRightClick, PowerOffhandClick, PowerProjectileHit, PowerSneak, PowerSprint, PowerOffhandItem, PowerMainhandItem, PowerTick, PowerSneaking, PowerPlain, PowerBowShoot {

        @Override
        public PowerResult<Void> leftClick(Player player, ItemStack stack, PlayerInteractEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> fire(Player player, ItemStack stack) {
            if (!checkCooldown(getPower(), player, getCooldown(), true, true)) return PowerResult.cd();
            if (!getItem().consumeDurability(stack, getCost())) return PowerResult.cost();

            org.bukkit.scoreboard.Scoreboard scoreboard = player.getScoreboard();

            Objective objective = scoreboard.getObjective(getObjective());
            if (objective != null) {
                Score sc = objective.getScore(player.getName());
                int ori = sc.getScore();
                switch (getScoreOperation()) {
                    case ADD_SCORE:
                        sc.setScore(ori + getValue());
                        break;
                    case SET_SCORE:
                        sc.setScore(getValue());
                        break;
                    case RESET_SCORE:
                        sc.setScore(0);
                        break;
                    default:
                }
            }
            if (getTeam() != null) {
                Pair<Set<String>, Set<String>> team = teamCache.getUnchecked(getTeam());
                team.getKey().stream().map(scoreboard::getTeam).forEach(t -> t.addEntry(player.getName()));
                team.getValue().stream().map(scoreboard::getTeam).forEach(t -> t.removeEntry(player.getName()));
            }

            if (getTag() != null) {
                Pair<Set<String>, Set<String>> tag = tagCache.getUnchecked(getTag());
                List<String> addedTags = new ArrayList<>();
                List<String> removedTags = new ArrayList<>();
                if (removeTask != null) {
                    if (!removeTask.isCancelled()) {
                        removeTask.cancel();
                        removeTask.run();
                    }
                }
                tag.getKey().forEach(tag1 -> {
                    if (player.addScoreboardTag(tag1)) {
                        addedTags.add(tag1);
                    }
                });
                tag.getValue().forEach(tag1 -> {
                    if (player.removeScoreboardTag(tag1)) {
                        removedTags.add(tag1);
                    }
                });
                if (isReverseTagAfterDelay()) {
                    removeTask = new BukkitRunnable() {
                        @Override
                        public void run() {
                            addedTags.forEach(player::removeScoreboardTag);
                            removedTags.forEach(player::addScoreboardTag);
                        }
                    };
                    removeTask.runTaskLater(RPGItems.plugin, getDelay());
                }
            }
            return isAbortOnSuccess() ? PowerResult.abort() : PowerResult.ok();
        }

        @Override
        public Power getPower() {
            return Scoreboard.this;
        }

        @Override
        public PowerResult<Void> rightClick(Player player, ItemStack stack, PlayerInteractEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Double> hit(Player player, ItemStack stack, LivingEntity entity, double damage, EntityDamageByEntityEvent event) {
            return fire(player, stack).with(damage);
        }

        @Override
        public PowerResult<Double> takeHit(Player target, ItemStack stack, double damage, EntityDamageEvent event) {
            if (!isRequireHurtByEntity() || event instanceof EntityDamageByEntityEvent) {
                return fire(target, stack).with(damage);
            }
            return PowerResult.noop();
        }

        @Override
        public PowerResult<Void> hurt(Player target, ItemStack stack, EntityDamageEvent event) {
            if (!isRequireHurtByEntity() || event instanceof EntityDamageByEntityEvent) {
                return fire(target, stack);
            }
            return PowerResult.noop();
        }

        @Override
        public PowerResult<Void> offhandClick(Player player, ItemStack stack, PlayerInteractEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> projectileHit(Player player, ItemStack stack, ProjectileHitEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> sneak(Player player, ItemStack stack, PlayerToggleSneakEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> sprint(Player player, ItemStack stack, PlayerToggleSprintEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Float> bowShoot(Player player, ItemStack itemStack, EntityShootBowEvent e) {
            return fire(player, itemStack).with(e.getForce());
        }

        @Override
        public PowerResult<Boolean> swapToMainhand(Player player, ItemStack stack, PlayerSwapHandItemsEvent event) {
            return fire(player, stack).with(true);
        }

        @Override
        public PowerResult<Boolean> swapToOffhand(Player player, ItemStack stack, PlayerSwapHandItemsEvent event) {
            return fire(player, stack).with(true);
        }

        @Override
        public PowerResult<Void> tick(Player player, ItemStack stack) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> sneaking(Player player, ItemStack stack) {
            return fire(player, stack);
        }
    }
}