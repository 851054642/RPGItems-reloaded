package think.rpgitems.power.impl;

import cat.nyaa.nyaacore.Message;
import com.google.common.util.concurrent.AtomicDouble;
import com.udojava.evalex.Expression;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import think.rpgitems.RPGItems;
import think.rpgitems.data.LightContext;
import think.rpgitems.power.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static think.rpgitems.Events.*;
import static think.rpgitems.power.Utils.checkCooldown;

/**
 * @author <a href="mailto:ReinWDD@gmail.com">ReinWD</a>
 *
 * Wrote &amp; Maintained by ReinWD
 * if you have any issue, please send me email or @ReinWD in issues.
 * Accepted language: 中文, English.
 */
@Meta(defaultTrigger = "RIGHT_CLICK", generalInterface = PowerPlain.class, implClass = Beam.Impl.class)
public class Beam extends BasePower {
    @Property
    public int length = 10;

    @Property
    public int ttl = 100;

    @Property
    public Particle particle = Particle.LAVA;

    @Property
    public Mode mode = Mode.BEAM;

    @Property
    public int pierce = 0;

    @Property
    public boolean ignoreWall = true;

    @Property
    public double damage = 20;

    @Property
    public double speed = 20;

    @Property
    public double offsetX = 0;

    @Property
    public double offsetY = 0;

    @Property
    public double offsetZ = 0;

    @Property
    public double particleSpeed = 0;

    @Property
    public double spawnsPerBlock = 2;

    /**
     * Cost of this power
     */
    @Property
    public int cost = 0;
    /**
     * Cooldown time of this power
     */
    @Property
    public long cooldown = 0;

//  used to judge legacy 1.0
//    @Property
//    public boolean cone = false;

    @Property
    public double cone = 10;

    @Property
    public double homing = 0;

    @Property
    public double homingAngle = 30;

    @Property
    public double homingRange = 50;

    @Property
    public HomingMode homingMode = HomingMode.ONE_TARGET;

    @Property
    public Target homingTarget = Target.MOBS;

    @Property
    public int ticksBeforeHoming = 0;

    @Property
    public int burstCount = 1;

    @Property
    public int beamAmount = 1;

    @Property
    public int burstInterval = 10;

    @Property
    public int bounce = 0;

    @Property
    public boolean hitSelfWhenBounced = false;

    @Property
    public double gravity = 0;

    @Property
    @Serializer(ExtraDataSerializer.class)
    @Deserializer(ExtraDataSerializer.class)
    public Object extraData;

    @Property
    public boolean requireHurtByEntity = true;


    @Property
    public boolean suppressMelee = false;

    @Property
    public String speedBias = "";

    @Property
    public Behavior behavior = Behavior.PLAIN;

    @Property
    public String behaviorParam = "{}";

    @Override
    public void init(ConfigurationSection section) {
        if (section.contains("coneRange")) {
            updateFromV1(section);
        }
        super.init(section);
    }

    private void updateFromV1(ConfigurationSection section) {
        boolean originalCone = section.getBoolean("cone");
        boolean pierce = section.getBoolean("pierce");
        double cone = section.getDouble("coneRange");
        int movementTicks = section.getInt("movementTicks");
        int length = section.getInt("length");
        double originSpeed = section.getDouble("speed");
        double homing = 0;
        double homingAngle = section.getDouble("homingAngle");
        double homingRange = section.getDouble("homingRange");
        String homingTargetMode = section.getString("homingTargetMode");
        int stepsBeforeHoming = section.getInt("stepsBeforeHoming");

        double spd = ((double) length*20) / ((double) movementTicks);
        int spawnsPerBlock = section.getInt("spawnsPerBlock");
        double blockPerSpawn = 1 / ((double) spawnsPerBlock);
        double stepPerSecond = spd / blockPerSpawn;

        if (!section.getBoolean("homing")) {
            homingAngle = 0;
        }else {
            homing = blockPerSpawn / (2 *Math.cos( Math.toRadians(homingAngle)));
        }
        if(originalCone) {
            section.set("cone", cone);
        }else {
            section.set("cone", 0);
        }
        int pierceNum = 0;
        if (pierce){
            pierceNum = 50;
        }
        section.set("speed", spd);
        section.set("particleSpeed", originSpeed);
        section.set("homing", homing);
        section.set("homingAngle", homingRange);
        section.set("homingMode", homingTargetMode);
        section.set("pierce", pierceNum);
        section.set("behavior", "LEGACY_HOMING");
        section.set("ticksBeforeHoming", stepsBeforeHoming);
        section.set("ttl", ((int) Math.floor(length*20 / spd)));
        section.set("homingRange", length);
    }

    private static Set<Material> transp = Stream.of(Material.values())
            .filter(material -> material.isBlock())
            .filter(material -> !material.isSolid() || !material.isOccluding())
            .collect(Collectors.toSet());

    final Vector crosser = new Vector(1, 1, 1);
    private Random random = new Random();

    public int getBeamAmount() {
        return beamAmount;
    }

    public Behavior getBehavior() {
        return behavior;
    }

    public String getBehaviorParam() {
        return behaviorParam;
    }

    public int getBounce() {
        return bounce;
    }

    public int getBurstCount() {
        return burstCount;
    }

    public int getBurstInterval() {
        return burstInterval;
    }

    public double getCone() {
        return cone;
    }

    public double getDamage() {
        return damage;
    }

    public Object getExtraData() {
        return extraData;
    }

    public double getGravity() {
        return gravity;
    }

    public double getHoming() {
        return homing;
    }

    public double getHomingAngle() {
        return homingAngle;
    }

    public HomingMode getHomingMode() {
        return homingMode;
    }

    public double getHomingRange() {
        return homingRange;
    }

    public Target getHomingTarget() {
        return homingTarget;
    }

    public int getLength() {
        return length;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    public String getName() {
        return "beam";
    }

    @Override
    public String displayText() {
        return null;
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public double getOffsetZ() {
        return offsetZ;
    }

    public Particle getParticle() {
        return particle;
    }

    public double getParticleSpeed() {
        return particleSpeed;
    }

    public int getPierce() {
        return pierce;
    }

    public double getSpawnsPerBlock() {
        return spawnsPerBlock;
    }

    public double getSpeed() {
        return speed;
    }

    public String getSpeedBias() {
        return speedBias;
    }

    public int getTicksBeforeHoming() {
        return ticksBeforeHoming;
    }

    public int getTtl() {
        return ttl;
    }

    public boolean isHitSelfWhenBounced() {
        return hitSelfWhenBounced;
    }

    public boolean isIgnoreWall() {
        return ignoreWall;
    }

    /**
     * Whether to suppress the hit trigger
     */
    public boolean isSuppressMelee() {
        return suppressMelee;
    }

    public int getCost() {
        return cost;
    }

    public long getCooldown() {
        return cooldown;
    }

    public boolean isRequireHurtByEntity() {
        return requireHurtByEntity;
    }

    private static class MovingTask extends BukkitRunnable {
        private double homingRange;
        private int length = 10;
        private int ttl = 200;
        private Particle particle = Particle.LAVA;
        private Mode mode = Mode.BEAM;
        private int pierce = 0;
        private boolean ignoreWall = true;
        private double damage = 20;
        private double speed = 20;
        private double offsetX = 0;
        private double offsetY = 0;
        private double offsetZ = 0;
        private double particleSpeed = 0;
        private double spawnsPerBlock = 2;
        private double homing = 0;
        private double homingAngle = 30;
        private Target homingTarget = Target.MOBS;
        private HomingMode homingMode = HomingMode.ONE_TARGET;
        private int ticksBeforeHoming = 5;
        private int bounce = 0;
        private boolean hitSelfWhenBounced = false;
        private double gravity = 0;
        private boolean suppressMelee = false;
        private Behavior behavior = Behavior.PLAIN;
        private String behaviorParam = "{}";
        private Object extraData = null;
        private Beam power;
        private String speedBias = "";

        private Queue<Entity> targets = new LinkedList<>();
        private Entity fromEntity;
        private Location fromLocation;
        private Vector towards;
        double lengthPerSpawn;

        AtomicDouble lengthRemains = new AtomicDouble(0);
        AtomicDouble spawnedLength = new AtomicDouble(0);
        AtomicInteger currentTick = new AtomicInteger(0);
        Vector gravityVector = new Vector(0, 0, 0);
        Location lastLocation;
        private ItemStack itemStack;
        boolean bounced = false;
        World world;
        Set<UUID> hitMob = new HashSet<>();
        int cycle = 0;

        MovingTask(Beam config) {
            this.length = config.getLength();
            this.ttl = config.getTtl();
            this.particle = config.getParticle();
            this.mode = config.getMode();
            this.pierce = config.getPierce();
            this.ignoreWall = config.isIgnoreWall();
            this.damage = config.getDamage();
            this.speed = config.getSpeed();
            this.offsetX = config.getOffsetX();
            this.offsetY = config.getOffsetY();
            this.offsetZ = config.getOffsetZ();
            this.spawnsPerBlock = config.getSpawnsPerBlock();
            this.homing = config.getHoming();
            this.homingMode = config.getHomingMode();
            this.ticksBeforeHoming = config.getTicksBeforeHoming();
            this.bounce = config.getBounce();
            this.hitSelfWhenBounced = config.isHitSelfWhenBounced();
            this.gravity = config.getGravity();
            this.particleSpeed = config.getParticleSpeed();
            this.suppressMelee = config.isSuppressMelee();
            this.behavior = config.getBehavior();
            this.behaviorParam = config.getBehaviorParam();
            this.extraData = config.getExtraData();
            this.speedBias = config.getSpeedBias();
            this.homingAngle = config.getHomingAngle();
            this.homingTarget = config.getHomingTarget();
            this.homingRange = config.getHomingRange();
            power = config;
            lengthPerSpawn = 1 / spawnsPerBlock;
        }

        @Override
        public void run() {
            world = fromLocation.getWorld();
            if (world == null) return;
            if (Double.isInfinite(lengthPerSpawn)) {
                return;
            }
            lastLocation = fromLocation;
            towards.normalize();
            new RecursiveTask().runTask(RPGItems.plugin);
        }

        public void setItemStack(ItemStack stack) {
            this.itemStack = stack;
        }

        class RecursiveTask extends BukkitRunnable {
            @Override
            public void run() {
                try {
                    double lengthInThisTick = getNextLength(spawnedLength, length) + lengthRemains.get();

                    double lengthToSpawn = lengthInThisTick;
                    if (mode.equals(Mode.BEAM)) {
                        lengthToSpawn = length;
                    }
                    int hitCount = 0;
                    while ((lengthToSpawn -= lengthPerSpawn) > 0) {
                        hitMob.addAll(tryHit(fromEntity, lastLocation, itemStack, bounced && hitSelfWhenBounced));

                        if (cycle++ > 2 / lengthPerSpawn) {
                            hitMob.clear();
                            hitCount = 0;
                            cycle = 0;
                            if (homingMode.equals(HomingMode.MOUSE_TRACK)) {
                                Location location = fromEntity.getLocation();
                                if (fromEntity instanceof LivingEntity) {
                                    location = ((LivingEntity) fromEntity).getEyeLocation();
                                }
                                targets = new LinkedList<>(getTargets(location.getDirection(), location, fromEntity, homingRange, homingAngle, homingTarget));
                            }
                        }

                        spawnParticle(fromEntity, world, lastLocation, 1);
                        Vector step = towards.clone().normalize().multiply(lengthPerSpawn);
                        if (gravity != 0 && (
                                homing == 0 || currentTick.get() < ticksBeforeHoming
                        ) ) {
                            double partsPerTick = lengthInThisTick / lengthPerSpawn;
                            step.setY(step.getY() + getGravity(partsPerTick));
                        }
                        Location nextLoc = lastLocation.clone().add(step);
                        if (!ignoreWall && (
                                nextLoc.getBlockX() != lastLocation.getBlockX() ||
                                        nextLoc.getBlockY() != lastLocation.getBlockY() ||
                                        nextLoc.getBlockZ() != lastLocation.getBlockZ()
                        )) {
                            if (!transp.contains(nextLoc.getBlock().getType())) {
                                if (bounce > 0) {
                                    bounce--;
                                    bounced = true;
                                    makeBounce(nextLoc.getBlock(), towards, step, lastLocation);
                                } else return;
                            }
                        }
                        lastLocation = nextLoc;
                        spawnedLength.addAndGet(lengthPerSpawn);
                        int dHit = hitMob.size() - hitCount;
                        if (dHit > 0) {
                            hitCount = hitMob.size();
                            pierce -= dHit;
                            if (pierce > 0) {
                                if (homingMode.equals(HomingMode.MULTI_TARGET)) {
                                    if (targets != null) {
                                        targets.removeIf(entity -> hitMob.contains(entity.getUniqueId()));
                                    }
                                }
                            } else {
                                return;
                            }
                        }
                        if (targets != null && homing > 0 && currentTick.get() >= ticksBeforeHoming) {
                            towards = homingCorrect(step, lastLocation, targets.peek(), () -> {
                                targets.removeIf(Entity::isDead);
                                return targets.peek();
                            });
                        }
                    }

                    lengthRemains.set(lengthToSpawn + lengthPerSpawn);
                    if (spawnedLength.get() >= length || currentTick.addAndGet(1) > ttl) {
                        return;
                    }
                    new RecursiveTask().runTaskLater(RPGItems.plugin, 1);
                } catch (Exception e) {
                    this.cancel();
                }
            }

            boolean reported = false;

            private double getNextLength(AtomicDouble spawnedLength, int length) {
                Expression eval = new Expression(speedBias).with("x", new Expression.LazyNumber() {
                    @Override
                    public BigDecimal eval() {
                        return BigDecimal.valueOf(spawnedLength.get() / ((double) length));
                    }

                    @Override
                    public String getString() {
                        return String.valueOf(spawnedLength.get() / ((double) length));
                    }
                }).with("t", new Expression.LazyNumber() {
                    @Override
                    public BigDecimal eval() {
                        return BigDecimal.valueOf(currentTick.get() / 20d);
                    }

                    @Override
                    public String getString() {
                        return null;
                    }
                });
                double v = 1;
                if (!"".equals(speedBias)) {
                    try {
                        v = (eval.eval().doubleValue());
                    } catch (Exception ignored) {
                        //todo: lang
                        if (!reported) {
                            new Message("invalid expression, please contact Admin").send(fromEntity);
                            reported = true;
                        }

                    }
                }
                return (speed + v) / 20;
            }
        }

        private void makeBounce(Block block, Vector towards, Vector step, Location lastLocation) {
            RayTraceResult rayTraceResult = block.rayTrace(lastLocation, step, towards.length() * 2, FluidCollisionMode.NEVER);
            if (rayTraceResult == null) {
                return;
            } else {
                BlockFace hitBlockFace = rayTraceResult.getHitBlockFace();
                if (hitBlockFace == null) return;
                Block relative = block.getRelative(hitBlockFace);
                if (!transp.contains(relative.getType())) {
                    RayTraceResult relativeResult = relative.rayTrace(lastLocation, step, towards.length() * 2, FluidCollisionMode.NEVER);
                    if (relativeResult != null) {
                        if (relativeResult.getHitBlockFace().getDirection().getY() > 0) {
                            gravityVector.multiply(-1);
                        }
                        towards.rotateAroundNonUnitAxis(relativeResult.getHitBlockFace().getDirection(), Math.toRadians(180)).multiply(-1);
                    }
                } else {
                    if (hitBlockFace.getDirection().getY() > 0) {
                        gravityVector.multiply(-1);
                    }
                    towards.rotateAroundNonUnitAxis(hitBlockFace.getDirection(), Math.toRadians(180)).multiply(-1);
                }
            }
        }

        double legacyBonus = 0;
        double lastCorrected = 0;

        private Vector homingCorrect(Vector towards, Location lastLocation, Entity target, Supplier<Entity> runnable) {
            if (target == null) {
                return towards;
            }
            if (target.isDead()) {
                target = runnable.get();
                if (target == null)return towards;
            }
            Location targetLocation;
            if (target instanceof LivingEntity) {
                targetLocation = ((LivingEntity) target).getEyeLocation();
            } else {
                targetLocation = target.getLocation();
            }

            Vector clone = towards.clone();
            Vector targetDirection = targetLocation.toVector().subtract(lastLocation.toVector());
            float angle = clone.angle(targetDirection);
            Vector crossProduct = clone.clone().getCrossProduct(targetDirection);
            //make sure path is a circle
            if (lastCorrected>0){
                clone.rotateAroundAxis(crossProduct, lastCorrected);
            }
            //legacy
//            double actualAng = (homing / 20) / (lengthInThisTick / lengthPerSpawn);
            double actualAng = Math.asin(towards.length() / (2 * homing));
            if (angle > actualAng) {
                if (this.behavior.equals(Behavior.LEGACY_HOMING)) {
                    double lastActualAngle = Math.asin(towards.length() / (2*(homing + legacyBonus)));
                    legacyBonus += (lastActualAngle / (Math.PI));
                    actualAng = Math.asin(towards.length() / (2 * (homing+legacyBonus)));
                }
                // ↓a better way to rotate.
                // will create a exact circle.
                clone.rotateAroundAxis(crossProduct, actualAng);
                lastCorrected = actualAng;
            } else {
                clone = targetDirection.normalize();
                lastCorrected = 0;
            }
            return clone;
        }

        private double spawnInWorld = 0;

        private void spawnParticle(Entity from, World world, Location lastLocation, int i) {
            Location eyeLocation;
            if (from instanceof Player) {
                eyeLocation = ((Player) from).getEyeLocation();
                if ((lastLocation.distance(eyeLocation) < 1)) {
                    return;
                }
                if (spawnInWorld >=3) {
                    ((Player) from).spawnParticle(this.particle, lastLocation, i, offsetX, offsetY, offsetZ, particleSpeed, extraData);
                    spawnInWorld = 0;
                } else {
                    world.spawnParticle(this.particle, lastLocation, i, offsetX, offsetY, offsetZ, particleSpeed, extraData, false);
                }
                spawnInWorld++;
            } else {
                world.spawnParticle(this.particle, lastLocation, i, offsetX, offsetY, offsetZ, particleSpeed, extraData, false);
            }

        }

        private Collection<? extends UUID> tryHit(Entity from, Location loc, ItemStack stack, boolean canHitSelf) {
            HashSet<UUID> hitMobs = new HashSet<>();
            if (from == null) return hitMobs;
            double offsetLength = new Vector(offsetX, offsetY, offsetZ).length();
            double length = Double.isNaN(offsetLength) ? 0.1 : Math.max(offsetLength, 10);
            Collection<Entity> candidates = from.getWorld().getNearbyEntities(loc, length, length, length);
            List<Entity> collect = candidates.stream()
                    .filter(entity -> (entity instanceof LivingEntity) && (!isUtilArmorStand((LivingEntity) entity)) && (canHitSelf || !entity.equals(from)) && !entity.isDead())
                    .filter(entity -> canHit(loc, entity))
                    .limit(Math.max(pierce,1))
                    .collect(Collectors.toList());
            if (!collect.isEmpty()) {
                Entity entity = collect.get(0);
                if (entity instanceof LivingEntity) {
                    LightContext.putTemp(from.getUniqueId(), DAMAGE_SOURCE, power.getNamespacedKey().toString());
                    LightContext.putTemp(from.getUniqueId(), OVERRIDING_DAMAGE, damage);
                    LightContext.putTemp(from.getUniqueId(), SUPPRESS_MELEE, suppressMelee);
                    LightContext.putTemp(from.getUniqueId(), DAMAGE_SOURCE_ITEM, stack);
                    ((LivingEntity) entity).damage(damage, from);
                    LightContext.clear();
                    hitMobs.add(entity.getUniqueId());
                }
            }
            return hitMobs;
        }

        private boolean canHit(Location loc, Entity entity) {
            BoundingBox boundingBox = entity.getBoundingBox();
            BoundingBox particleBox;
            double x = Math.max(offsetX, 0.1);
            double y = Math.max(offsetY, 0.1);
            double z = Math.max(offsetZ, 0.1);
            particleBox = BoundingBox.of(loc, x + 0.1, y + 0.1, z + 0.1);
            return boundingBox.overlaps(particleBox) || particleBox.overlaps(boundingBox);
        }

        private double getGravity(double partsPerTick) {
            double gravityPerTick = (-gravity / 20d) / partsPerTick;
            gravityVector.setY(gravityVector.getY() + gravityPerTick);
            return (gravityVector.getY() / 20d) / partsPerTick;
        }

        public void setTarget(Queue<Entity> targets) {
            this.targets = targets;
        }

        public void setFromEntity(Entity fromEntity) {
            this.fromEntity = fromEntity;
            if (fromEntity instanceof LivingEntity) {
                this.fromLocation = ((LivingEntity) fromEntity).getEyeLocation();
            } else {
                this.fromLocation = fromEntity.getLocation();
            }
            this.towards = fromLocation.getDirection();
        }

        public void setFromLocation(Location from) {
            this.fromLocation = from;
        }

        public void setTowards(Vector towards) {
            this.towards = towards;
        }
    }

    // can be called anywhere, maybe
    class MovingTaskBuilder {
        MovingTask movingTask;

        public MovingTaskBuilder(Beam power) {
            this.movingTask = new MovingTask(power);
        }

        public MovingTaskBuilder towards(Vector towards) {
            movingTask.setTowards(towards);
            return this;
        }

        public MovingTaskBuilder fromLocation(Location location) {
            movingTask.setFromLocation(location);
            return this;
        }

        public MovingTaskBuilder fromEntity(Entity entity) {
            movingTask.setFromEntity(entity);
            return this;
        }

        public MovingTaskBuilder targets(Queue<Entity> targets) {
            movingTask.setTarget(targets);
            return this;
        }

        public MovingTaskBuilder itemStack(ItemStack stack) {
            movingTask.setItemStack(stack);
            return this;
        }

        public MovingTask build() {
            return movingTask;
        }
    }

    private static boolean isUtilArmorStand(LivingEntity livingEntity) {
        if (livingEntity instanceof ArmorStand) {
            ArmorStand arm = (ArmorStand) livingEntity;
            return arm.isMarker() && !arm.isVisible();
        }
        return false;
    }

    private static List<Entity> getTargets(Vector direction, Location fromLocation, Entity from, double range, double homingAngle, Target homingTarget) {
        double radius = Math.min(range, 300);
        return Utils.getLivingEntitiesInConeSorted(from.getNearbyEntities(radius, range * 1.5, range * 1.5).stream()
                        .filter(entity -> entity instanceof LivingEntity && !entity.equals(from) && !entity.isDead())
                        .map(entity -> ((LivingEntity) entity))
                        .collect(Collectors.toList())
                , fromLocation.toVector(), homingAngle, direction).stream()
                .filter(livingEntity -> {
                    if (isUtilArmorStand(livingEntity)) {
                        return false;
                    }
                    switch (homingTarget) {
                        case MOBS:
                            return !(livingEntity instanceof Player);
                        case PLAYERS:
                            return livingEntity instanceof Player && !((Player) livingEntity).getGameMode().equals(GameMode.SPECTATOR);
                        case ALL:
                            return !(livingEntity instanceof Player) || !((Player) livingEntity).getGameMode().equals(GameMode.SPECTATOR);
                    }
                    return true;
                }).collect(Collectors.toList());
    }

    private enum Mode {
        BEAM,
        PROJECTILE,
        ;

    }

    public class ExtraDataSerializer implements Getter, Setter {
        @Override
        public String get(Object object) {
            if (object instanceof Particle.DustOptions) {
                Color color = ((Particle.DustOptions) object).getColor();
                return color.getRed() + "," + color.getGreen() + "," + color.getBlue() + "," + ((Particle.DustOptions) object).getSize();
            }
            return "";
        }

        @Override
        public Optional set(String value) throws IllegalArgumentException {
            if("null".equals(value)){
                return Optional.empty();
            }
            String[] split = value.split(",", 4);
            int r = Integer.parseInt(split[0]);
            int g = Integer.parseInt(split[1]);
            int b = Integer.parseInt(split[2]);
            float size = Float.parseFloat(split[3]);
            return Optional.of(new Particle.DustOptions(Color.fromRGB(r, g, b), size));
        }
    }

    enum Target {
        MOBS, PLAYERS, ALL
    }

    public enum Behavior {
        PLAIN(PlainBias.class, Void.class),
        DNA(DnaBias.class, DnaBias.DnaParams.class),
        CIRCLE(CircleBias.class, CircleBias.CircleParams.class),
        LEGACY_HOMING(PlainBias.class, Void.class);

        private Class<? extends IBias> iBias;
        private Class<?> paramType;

        Behavior(Class<? extends IBias> iBias, Class<?> paramType) {
            this.iBias = iBias;
            this.paramType = paramType;
        }

        public List<Vector> getBiases(Location location, Vector towards, MovingTask context, String params) {
            return null;
        }
    }

    interface IBias<T> {
        List<Vector> getBiases(Location location, Vector towards, MovingTask context, T params);
    }

    static class PlainBias implements IBias<Void> {
        @Override
        public List<Vector> getBiases(Location location, Vector towards, MovingTask context, Void params) {
            return null;
        }
    }

    static class CircleBias implements IBias<CircleBias.CircleParams> {
        private CircleParams params;

        @Override
        public List<Vector> getBiases(Location location, Vector towards, MovingTask context, CircleParams params) {
            return null;
        }

        static class CircleParams {
            public double r = 1;
            public String rFunc = "";

        }
    }

    static class DnaBias implements IBias<DnaBias.DnaParams> {
        @Override
        public List<Vector> getBiases(Location location, Vector towards, MovingTask context, DnaParams params) {
            return null;
        }

        static class DnaParams {
            double amount = 2;
            double r = 1;
            String rFunc = "";
        }
    }

    private enum HomingMode {
        ONE_TARGET, MULTI_TARGET, MOUSE_TRACK
    }

    public class Impl implements PowerPlain, PowerRightClick, PowerLeftClick, PowerSneak, PowerSneaking, PowerSprint, PowerBowShoot, PowerHitTaken, PowerHit, PowerHurt {
        @Override
        public PowerResult<Void> leftClick(Player player, ItemStack stack, PlayerInteractEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> fire(Player player, ItemStack stack) {
            if (!checkCooldown(getPower(), player, getCooldown(), true, true)) return PowerResult.cd();
            if (!getItem().consumeDurability(stack, getCost())) return PowerResult.cost();
            return beam(player, stack);
        }

        @Override
        public Power getPower() {
            return Beam.this;
        }

        private PowerResult<Void> beam(LivingEntity from, ItemStack stack) {
            if (getBurstCount() > 0) {
                final int currentBurstCount = getBurstCount();
                final int currentBurstInterval = getBurstInterval();
                AtomicInteger bursted = new AtomicInteger(0);
                class FireTask extends BukkitRunnable {
                    @Override
                    public void run() {
                        for (int j = 0; j < getBeamAmount(); j++) {
                            internalFireBeam(from, stack);
                        }
                        if (bursted.addAndGet(1) < currentBurstCount) {
                            new FireTask().runTaskLater(RPGItems.plugin, currentBurstInterval);
                        }
                    }
                }
                new FireTask().runTask(RPGItems.plugin);
                return PowerResult.ok();
            } else {
                return internalFireBeam(from, stack);
            }
        }

        private PowerResult<Void> internalFireBeam(LivingEntity from, ItemStack stack) {
            Location fromLocation = from.getEyeLocation();
            Vector towards = from.getEyeLocation().getDirection();

            if (getCone() != 0) {
                double phi = random.nextDouble() * 360;
                double theta;
                theta = random.nextDouble() * getCone();
                Vector clone = towards.clone();
                Vector cross = clone.clone().add(crosser);
                Vector vertical = clone.getCrossProduct(cross).getCrossProduct(towards);
                towards.rotateAroundAxis(vertical, Math.toRadians(theta));
                towards.rotateAroundAxis(clone, Math.toRadians(phi));
            }


            Queue<Entity> targets = null;
            if (from instanceof Player && getHoming() > 0) {
                targets = new LinkedList<>(getTargets(from.getEyeLocation().getDirection(), fromLocation, from, getHomingRange(), getHomingAngle(), getHomingTarget()));
            }
            MovingTask movingTask = new MovingTaskBuilder(Beam.this)
                    .fromEntity(from)
                    .towards(towards)
                    .targets(targets)
                    .itemStack(stack)
                    .build();
            movingTask.runTask(RPGItems.plugin);
            return PowerResult.ok();
        }

        @Override
        public PowerResult<Void> rightClick(Player player, ItemStack stack, PlayerInteractEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> sneak(Player player, ItemStack stack, PlayerToggleSneakEvent event) {
            return fire(player, stack);
        }

        @Override
        public PowerResult<Void> sneaking(Player player, ItemStack stack) {
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
        public PowerResult<Double> hit(Player player, ItemStack stack, LivingEntity entity, double damage, EntityDamageByEntityEvent event) {
            return fire(player, stack).with(event.getDamage());
        }

        @Override
        public PowerResult<Double> takeHit(Player target, ItemStack stack, double damage, EntityDamageEvent event) {
            if (!isRequireHurtByEntity() || event instanceof EntityDamageByEntityEvent) {
                return fire(target, stack).with(event.getDamage());
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
    }
}



