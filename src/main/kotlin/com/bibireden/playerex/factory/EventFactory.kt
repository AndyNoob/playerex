package com.bibireden.playerex.factory

import com.bibireden.data_attributes.api.DataAttributesAPI
import com.bibireden.playerex.PlayerEX
import com.bibireden.playerex.api.attribute.PlayerEXAttributes
import com.bibireden.playerex.components.player.PlayerDataComponent
import com.bibireden.playerex.ext.component
import com.bibireden.playerex.registry.DamageModificationRegistry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.AbstractArrow

object EventFactory {
    fun reset(oldPlayer: ServerPlayer, newPlayer: ServerPlayer, isAlive: Boolean)
    {
        val factor = if (PlayerEX.CONFIG.featureSettings.resetOnDeath) 0 else 100
        // attempt reconciliation
        (oldPlayer.component as PlayerDataComponent).modifiers.forEach { (rl, value) ->
            val attr = BuiltInRegistries.ATTRIBUTE[rl] ?: return@forEach
            newPlayer.component.set(attr, value.toInt())
        }
        newPlayer.component.reset(factor)
    }

    fun healed(entity: LivingEntity, amount: Float): Float
    {
        return DataAttributesAPI.getValue(PlayerEXAttributes.HEAL_AMPLIFICATION, entity).map { (amount * (1 + it)).toFloat() }.orElse(amount)
    }

    fun healthRegeneration(entity: LivingEntity)
    {
        if (!entity.level().isClientSide()) {
            DataAttributesAPI.getValue(PlayerEXAttributes.HEALTH_REGENERATION, entity).ifPresent { value ->
                if (value > 0.0 && entity.health < entity.maxHealth) {
                    entity.heal(value.toFloat())
                }
            }
        }
    }

    fun onDamage(livingEntity: LivingEntity, source: DamageSource, original: Float): Float
    {
        var amount = original
        for (condition in DamageModificationRegistry.get()) {
            val damage = amount
            amount = condition.provide { predicate, function ->
                return@provide if (predicate.test(livingEntity, source, damage)) {
                    function.apply(livingEntity, source, damage)
                }
                else {
                    damage
                }
            }
        }
        return amount
    }

    fun shouldDamage(livingEntity: LivingEntity, source: DamageSource, original: Float): Boolean
    {
        if (original == 0.0F) return true

        val origin: Entity? = source.directEntity
        val attacker: Entity? = source.entity

        if (attacker is LivingEntity && (origin is LivingEntity || origin is AbstractArrow))
        {
            DataAttributesAPI.getValue(PlayerEXAttributes.LIFESTEAL, attacker).ifPresent {
                attacker.heal((original * it).toFloat())
            }
        }

        return DataAttributesAPI.getValue(PlayerEXAttributes.EVASION, livingEntity).map {
            !(livingEntity.random.nextFloat() < it && origin is AbstractArrow)
        }.orElse(true)
    }

    fun onCritAttack(player: Player, target: Entity, amount: Float): Float
    {
        if (target !is LivingEntity) return amount
        return DataAttributesAPI.getValue(PlayerEXAttributes.MELEE_CRITICAL_DAMAGE, player)
            .map { (amount * (1.0 + (it * 10.0)) / 1.5).toFloat() }
            .orElse(amount)
    }

    fun attackIsCrit(player: Player, target: Entity, original: Boolean): Boolean
    {
        if (target !is LivingEntity) return original

        val critChanceOptional = DataAttributesAPI.getValue(PlayerEXAttributes.MELEE_CRITICAL_CHANCE, player)

        if (critChanceOptional.isPresent)
        {
            val chance = player.random.nextFloat()
            return (chance < critChanceOptional.get()) && !player.onClimbable() && !player.hasEffect(MobEffects.BLINDNESS) && !player.isPassenger
        }

        return original
    }

}