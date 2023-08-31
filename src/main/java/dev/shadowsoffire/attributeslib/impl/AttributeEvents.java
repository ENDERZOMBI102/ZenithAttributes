package dev.shadowsoffire.attributeslib.impl;

import java.util.Random;

import com.jamieswhiteshirt.reachentityattributes.ReachEntityAttributes;
import dev.shadowsoffire.attributeslib.AttributesLib;
import dev.shadowsoffire.attributeslib.api.*;
import dev.shadowsoffire.attributeslib.util.AttributesUtil;
import io.github.fabricators_of_create.porting_lib.entity.events.EntityEvents;
import io.github.fabricators_of_create.porting_lib.entity.events.living.LivingEntityDamageEvents;
import io.github.fabricators_of_create.porting_lib.entity.events.living.LivingEntityLootEvents;
import io.github.fabricators_of_create.porting_lib.entity.events.player.PlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntityElytraEvents;
import net.fabricmc.fabric.api.entity.event.v1.FabricElytraItem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.EntityHitResult;

public class AttributeEvents {
/*
    @SubscribeEvent
    public void fixChangedAttributes(PlayerLoggedInEvent e) {
        AttributeMap map = e.getEntity().getAttributes();
        map.getInstance(ForgeMod.STEP_HEIGHT_ADDITION).setBaseValue(0.6);
    }*/

    private static boolean canBenefitFromDrawSpeed(ItemStack stack) {
        return stack.getItem() instanceof ProjectileWeaponItem || stack.getItem() instanceof TridentItem;
    }

    public static void init(){
        elytra();
        drawSpeed();
        lifeStealOverheal();
        meleeDamageAttributes();
        apothCriticalStrike();
        breakSpd();
        heal();
        mobXp();
        dodgeMelee();
        dodgeProjectile();
        affixModifiers();
        trackCooldown();
        valueChanged(); //TODO init the event
    }

    /**
     * This handles the elytra attribute.
     */
    public static void elytra() {
        EntityElytraEvents.CUSTOM.register((entity, tickElytra) -> {
            if (entity instanceof Player p){
                return p.getAttributeValue(ALObjects.Attributes.ELYTRA_FLIGHT) == 1;
            }
            return false;
        });

    }

    /**
     * This event handler is the implementation for {link ALObjects#DRAW_SPEED}.<br>
     * Each full point of draw speed provides an extra using tick per game tick.<br>
     * Each partial point of draw speed provides an extra using tick periodically.
     */
    public static void drawSpeed() {
                 UseItemTickEvent.EVENT.register((entity, usingItem, useItemRemaining) -> {
                             if (entity instanceof Player player) {
                                 double t = player.getAttribute(ALObjects.Attributes.DRAW_SPEED).getValue() - 1;
                                 if (t == 0 || !canBenefitFromDrawSpeed(usingItem)) return useItemRemaining;

                                 // Handle negative draw speed.
                                 int offset = -1;
                                 if (t < 0) {
                                     offset = 1;
                                     t = -t;
                                 }

                                 while (t > 1) { // Every 100% triggers an immediate extra tick
                                     t--;
                                     return (useItemRemaining + offset);

                                 }

                                 if (t > 0.5F) { // Special case 0.5F so that values in (0.5, 1) don't round to 1.
                                     if (player.tickCount % 2 == 0) return (useItemRemaining + offset);
                                     t -= 0.5F;
                                 }

                                 int mod = (int) Math.floor(1 / Math.min(1, t));
                                 if (entity.tickCount % mod == 0) return (useItemRemaining + offset);
                                 t--;
                             }
                     return useItemRemaining;
                 });
             /*

*/

    }

    /**
     * This event handler manages the Life Steal and Overheal attributes.
     */
    public static void lifeStealOverheal() {
        LivingEntityDamageEvents.HURT.register(e -> {
            if (e.damageSource.getDirectEntity() instanceof LivingEntity attacker && AttributesUtil.isPhysicalDamage(e.damageSource)) {
                float lifesteal = (float) attacker.getAttributeValue(ALObjects.Attributes.LIFE_STEAL);
                float dmg = Math.min(e.damageAmount, attacker.getHealth());
                if (lifesteal > 0.001) {
                    attacker.heal(dmg * lifesteal);
                }
                float overheal = (float) attacker.getAttributeValue(ALObjects.Attributes.OVERHEAL);
                float maxOverheal = attacker.getMaxHealth() * 0.5F;
                if (overheal > 0 && attacker.getAbsorptionAmount() < maxOverheal) {
                    attacker.setAbsorptionAmount(Math.min(maxOverheal, attacker.getAbsorptionAmount() + dmg * overheal));
                }
            }
        });

    }

    /**
     * Recursion guard for {link #meleeDamageAttributes(LivingAttackEvent)}.<br>
     * Doesn't need to be ThreadLocal as attack logic is main-thread only.
     */
    private static boolean noRecurse = false;

    /**
     * Applies the following melee damage attributes:<br>
     * <ul>
     * <li>{link ALObjects#CURRENT_HP_DAMAGE}</li>
     * <li>{link ALObjects#FIRE_DAMAGE}</li>
     * <li>{link ALObjects#COLD_DAMAGE}</li>
     * </ul>
     */

    public static void meleeDamageAttributes() {
        LivingEntityDamageEvents.HURT.register(e -> {
            if (e.damaged.level().isClientSide) return;
            if (noRecurse) return;
            noRecurse = true;
            if (e.damageSource.getDirectEntity() instanceof LivingEntity attacker && AttributesUtil.isPhysicalDamage(e.damageSource)) {
                float hpDmg = (float) attacker.getAttributeValue(ALObjects.Attributes.CURRENT_HP_DAMAGE);
                float fireDmg = (float) attacker.getAttributeValue(ALObjects.Attributes.FIRE_DAMAGE);
                float coldDmg = (float) attacker.getAttributeValue(ALObjects.Attributes.COLD_DAMAGE);
                LivingEntity target = e.damaged;
                int time = target.invulnerableTime;
                target.invulnerableTime = 0;
                if (hpDmg > 0.001 && AttributesLib.localAtkStrength >= 0.85F) {
                    target.hurt(src(ALObjects.DamageTypes.CURRENT_HP_DAMAGE, attacker), AttributesLib.localAtkStrength * hpDmg * target.getHealth());
                }
                target.invulnerableTime = 0;
                if (fireDmg > 0.001 && AttributesLib.localAtkStrength >= 0.55F) {
                    target.hurt(src(ALObjects.DamageTypes.FIRE_DAMAGE, attacker), AttributesLib.localAtkStrength * fireDmg);
                    target.setRemainingFireTicks(target.getRemainingFireTicks() + (int) (10 * fireDmg));
                }
                target.invulnerableTime = 0;
                if (coldDmg > 0.001 && AttributesLib.localAtkStrength >= 0.55F) {
                    target.hurt(src(ALObjects.DamageTypes.COLD_DAMAGE, attacker), AttributesLib.localAtkStrength * coldDmg);
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) (15 * coldDmg), Mth.floor(coldDmg / 5)));
                }
                target.invulnerableTime = time;
            }
            noRecurse = false;
        });

    }

    private static DamageSource src(ResourceKey<DamageType> type, LivingEntity entity) {
        return entity.level().damageSources().source(type, entity);
    }

    /**
     * Handles {link ALObjects#CRIT_CHANCE} and {link ALObjects#CRIT_DAMAGE}
     */

    public static void apothCriticalStrike() {
        LivingEntityDamageEvents.HURT.register(e -> {
            LivingEntity attacker = e.damageSource.getEntity() instanceof LivingEntity le ? le : null;
            if (attacker == null) return;

            double critChance = attacker.getAttributeValue(ALObjects.Attributes.CRIT_CHANCE);
            float critDmg = (float) attacker.getAttributeValue(ALObjects.Attributes.CRIT_DAMAGE);

            RandomSource rand = e.damaged.getRandom();

            float critMult = 1.0F;

            // Roll for crits. Each overcrit reduces the effectiveness by 15%
            // We stop rolling when crit chance fails or the crit damage would reduce the total damage dealt.
            while (rand.nextFloat() <= critChance && critDmg > 1.0F) {
                critChance--;
                critMult *= critDmg;
                critDmg *= 0.85F;
            }

            e.damageAmount *= critMult;

            if (critMult > 1 && !attacker.level().isClientSide) {
                // TODO Packets :))) PacketDistro.sendToTracking(AttributesLib.CHANNEL, new CritParticleMessage(e.getEntity().getId()), (ServerLevel) attacker.level(), e.getEntity().blockPosition());
            }
        });

    }

    /**
     * Handles {link ALObjects#CRIT_DAMAGE}'s interactions with vanilla critical strikes.
     */
/*
    public void vanillaCritDmg(CriticalHitEvent e) { //TODO mixin into player to add a crit event, attack(entity)
        float critDmg = (float) e.getEntity().getAttributeValue(ALObjects.Attributes.CRIT_DAMAGE);
        if (e.isVanillaCritical()) {
            e.setDamageModifier(Math.max(e.getDamageModifier(), critDmg));
        }
    }

    /**
     * Handles {link ALObjects#MINING_SPEED}
     */

    public static void breakSpd() {
        PlayerEvents.BREAK_SPEED.register((player, state, pos, speed) -> (speed * (float) player.getAttributeValue(ALObjects.Attributes.MINING_SPEED)));
    }

    /**
     * This event, and {linkplain #mobXp(LivingExperienceDropEvent) the event below} handle {link ALObjects#EXPERIENCE_GAINED}
     */
/* //There is no clean way to do this with fabric sadly
    public void blockBreak() {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            double xpMult = player.getAttributeValue(ALObjects.Attributes.EXPERIENCE_GAINED);
            e.setExpToDrop((int) (e.getExpToDrop() * xpMult));
        });

    }*/


    public static void mobXp() {
        LivingEntityLootEvents.EXPERIENCE_DROP.register((i, attackingPlayer, player) -> {
            if (player == null) return i;
            double xpMult = player.getAttributeValue(ALObjects.Attributes.EXPERIENCE_GAINED);
            return ((int) (i * xpMult));
        });

    }

    /**
     * Handles {link ALObjects#HEALING_RECEIVED}
     */

    public static void heal() {
        HealEvent.EVENT.register((entity, amount) -> {
            if (!(entity instanceof Player player)) return amount;
            float factor = (float) player.getAttributeValue(ALObjects.Attributes.HEALING_RECEIVED);
            return (amount * factor);
        });

    }

    /**
     * Handles {link ALObjects#ARROW_DAMAGE} and {link ALObjects#ARROW_VELOCITY}
     */
 /*   public void arrow(EntityJoinLevelEvent e) { //TODO uh yeah this exists

        if (e.getEntity() instanceof AbstractArrow arrow) {
            if (arrow.level().isClientSide || arrow.getPersistentData().getBoolean("attributeslib.arrow.done")) return;
            if (arrow.getOwner() instanceof LivingEntity le) {
                arrow.setBaseDamage(arrow.getBaseDamage() * le.getAttributeValue(ALObjects.Attributes.ARROW_DAMAGE));
                arrow.setDeltaMovement(arrow.getDeltaMovement().scale(le.getAttributeValue(ALObjects.Attributes.ARROW_VELOCITY)));
            }
            arrow.getPersistentData().putBoolean("attributeslib.arrow.done", true);
        }
    }
*/
    /**
     * Copied from {link MeleeAttackGoal#getAttackReachSqr}
     */
    private static double getAttackReachSqr(Entity attacker, LivingEntity pAttackTarget) {
        return attacker.getBbWidth() * 2.0F * attacker.getBbWidth() * 2.0F + pAttackTarget.getBbWidth();
    }

    /**
     * Random used for dodge calculations.<br>
     * This random is seeded with the target entity's tick count before use.
     */
    private static Random dodgeRand = new Random();

    /**
     * Handles {link ALObjects#DODGE_CHANCE} for melee attacks.
     */
    public static void dodgeMelee() {
        LivingEntityDamageEvents.HURT.register(e -> {
            LivingEntity target = e.damaged;
            if (target.level().isClientSide) return;
            Entity attacker = e.damageSource.getDirectEntity();
            if (attacker instanceof LivingEntity) {
                if (!(target.getAttributes().hasAttribute(ALObjects.Attributes.DODGE_CHANCE))) return;
                double dodgeChance = target.getAttributeValue(ALObjects.Attributes.DODGE_CHANCE);
                double atkRangeSqr = attacker instanceof Player p ? getReach(p) * getReach(p) : getAttackReachSqr(attacker, target);
                dodgeRand.setSeed(target.tickCount);
                if (attacker.distanceToSqr(target) <= atkRangeSqr && dodgeRand.nextFloat() <= dodgeChance) {
                    onDodge(target);
                    e.setCanceled(true);
                }
            }
        });

    }

    private static double getReach(Player entity)
    {
        double range = entity.getAttributeValue(ReachEntityAttributes.ATTACK_RANGE);
        return range == 0 ? 0 : range + (entity.isCreative() ? 3 : 0);
    }
    /**
     * Handles {link ALObjects#DODGE_CHANCE} for projectiles.
     */

    public static void dodgeProjectile() {
        EntityEvents.PROJECTILE_IMPACT.register((projectile, hitResult) -> {
            Entity target = hitResult instanceof EntityHitResult entRes ? entRes.getEntity() : null;
            if (target instanceof LivingEntity lvTarget) {
                double dodgeChance = lvTarget.getAttributeValue(ALObjects.Attributes.DODGE_CHANCE);
                // We can skip the distance check for projectiles, as "Projectile Impact" means the projectile is on the target.
                dodgeRand.setSeed(target.tickCount);
                if (dodgeRand.nextFloat() <= dodgeChance) {
                    onDodge(lvTarget);
                    return true;
                }
            }
            return false;
        });

    }

    private static void onDodge(LivingEntity target) {
        target.level().playSound(null, target, ALObjects.Sounds.DODGE.get(), SoundSource.NEUTRAL, 1, 0.7F + target.getRandom().nextFloat() * 0.3F);
        if (target.level() instanceof ServerLevel sl) {
            double height = target.getBbHeight();
            double width = target.getBbWidth();
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, target.getX() - width / 4, target.getY(), target.getZ() - width / 4, 6, -width / 4, height / 8, -width / 4, 0);
        }
    }

    /**
     * Adds a fake modifier to show Attack Range to weapons with Attack Damage and Elytra flight info to items that allow for it.
     */

    public static void affixModifiers() {
        ItemAttributeModifierEvent.GATHER_TOOLTIPS.register(e -> {
            boolean hasBaseAD = e.nonChangableModifiers.get(Attributes.ATTACK_DAMAGE).stream().filter(m -> ((IFormattableAttribute) Attributes.ATTACK_DAMAGE).getBaseUUID().equals(m.getId())).findAny().isPresent();
            if (hasBaseAD) {
                boolean hasBaseAR = e.nonChangableModifiers.get(ReachEntityAttributes.REACH).stream().filter(m -> ((IFormattableAttribute) ReachEntityAttributes.REACH).getBaseUUID().equals(m.getId())).findAny().isPresent();
                if (!hasBaseAR) {
                    e.addModifier(ReachEntityAttributes.REACH, new AttributeModifier(AttributeHelper.BASE_ENTITY_REACH, () -> "attributeslib:fake_base_range", 0, Operation.ADDITION));
                }
            }
            if (e.slot == EquipmentSlot.CHEST && (e.stack.getItem() instanceof FabricElytraItem || e.stack.getItem() instanceof ElytraItem) && !e.nonChangableModifiers.containsKey(ALObjects.Attributes.ELYTRA_FLIGHT)) {
                e.addModifier(ALObjects.Attributes.ELYTRA_FLIGHT, new AttributeModifier(AttributeHelper.ELYTRA_FLIGHT_UUID, () -> "attributeslib:elytra_item_flight", 1, Operation.ADDITION));
            }
        });
    }

    public static void trackCooldown() {
        LivingEntityDamageEvents.HURT.register(e -> {
            if (e.damageSource.getDirectEntity() instanceof Player p){
                AttributesLib.localAtkStrength = p.getAttackStrengthScale(0.5F);
            }
        });

    }

    public static void valueChanged() {
    /*    AttributeChangedValueEvent e;
        // AttributesLib.LOGGER.info("Attribute {} changed value from {} to {}!", e.getAttributeInstance().getAttribute().getDescriptionId(), e.getOldValue(), e.getNewValue());
        if (e.getAttributeInstance().getAttribute() == ALObjects.Attributes.CREATIVE_FLIGHT && e.getEntity() instanceof ServerPlayer player) {

            boolean changed = false;

            if (((IFlying) player).getAndDestroyFlyingCache()) {
                player.getAbilities().flying = true;
                changed = true;
            }

            if (e.getNewValue() > 0) {
                player.getAbilities().mayfly = true;
                changed = true;
            }
            else if (e.getOldValue() > 0 && e.getNewValue() <= 0) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                changed = true;
            }

            if (changed) player.onUpdateAbilities();
        }*/
    }

    public static void applyCreativeFlightModifier(Player player, GameType newType) {
        AttributeInstance inst = player.getAttribute(ALObjects.Attributes.CREATIVE_FLIGHT);
        if (newType == GameType.CREATIVE && inst.getModifier(AttributeHelper.CREATIVE_FLIGHT_UUID) == null) {
            inst.addTransientModifier(new AttributeModifier(AttributeHelper.CREATIVE_FLIGHT_UUID, () -> "attributeslib:creative_flight", 1, Operation.ADDITION));
        }
        else {
            inst.removeModifier(AttributeHelper.CREATIVE_FLIGHT_UUID);
        }
    }
}