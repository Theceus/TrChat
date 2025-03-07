package me.arasple.mc.trchat.module.display.function.standard

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import me.arasple.mc.trchat.api.event.TrChatItemShowEvent
import me.arasple.mc.trchat.api.impl.BukkitProxyManager
import me.arasple.mc.trchat.api.nms.NMS
import me.arasple.mc.trchat.module.adventure.toNative
import me.arasple.mc.trchat.module.adventure.toTranslatableComponentAdventure
import me.arasple.mc.trchat.module.conf.file.Functions
import me.arasple.mc.trchat.module.display.function.Function
import me.arasple.mc.trchat.module.display.function.StandardFunction
import me.arasple.mc.trchat.module.internal.hook.HookPlugin
import me.arasple.mc.trchat.module.internal.hook.type.HookDisplayItem
import me.arasple.mc.trchat.module.internal.script.Reaction
import me.arasple.mc.trchat.util.*
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import taboolib.common.UnsupportedVersionException
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.util.asList
import taboolib.common.util.replaceWithOrder
import taboolib.common.util.resettableLazy
import taboolib.common5.util.parseMillis
import taboolib.library.xseries.XMaterial
import taboolib.module.chat.ComponentText
import taboolib.module.chat.Components
import taboolib.module.chat.impl.DefaultComponent
import taboolib.module.configuration.ConfigNode
import taboolib.module.configuration.ConfigNodeTransfer
import taboolib.module.nms.*
import taboolib.platform.Folia
import taboolib.platform.util.buildItem
import taboolib.platform.util.isAir
import taboolib.platform.util.sendLang

/**
 * @author ItsFlicker
 * @since 2022/3/12 19:14
 */
@StandardFunction
@PlatformSide(Platform.BUKKIT)
object ItemShow : Function("ITEM") {

    override val alias = "Item-Show"

    override val reaction by resettableLazy("functions") {
        Functions.conf["General.Item-Show.Action"]?.let { Reaction(it.asList()) }
    }

    @ConfigNode("General.Item-Show.Enabled", "function.yml")
    var enabled = true

    @ConfigNode("General.Item-Show.Permission", "function.yml")
    var permission = "none"

    @ConfigNode("General.Item-Show.Origin-Name", "function.yml")
    var originName = false

    @ConfigNode("General.Item-Show.Compatible", "function.yml")
    var compatible = false

//    @ConfigNode("General.Item-Show.UI", "function.yml")
    var ui = false

    @ConfigNode("General.Item-Show.Cooldown", "function.yml")
    val cooldown = ConfigNodeTransfer<String, Long> { parseMillis() }

    @ConfigNode("General.Item-Show.Keys", "function.yml")
    var keys = emptyList<String>()

    private val cacheComponent: Cache<ItemStack, ComponentText> = CacheBuilder.newBuilder()
        .maximumSize(50)
        .build()
    val cacheInventory: Cache<String, Inventory> = CacheBuilder.newBuilder()
        .maximumSize(50)
        .build()

    private val AIR_ITEM = buildItem(XMaterial.GRAY_STAINED_GLASS_PANE) { name = "§f" }

    override fun createVariable(sender: Player, message: String): String {
        if (!enabled) {
            return message
        }
        var result = message
        keys.forEach { key ->
            (1..9).forEach {
                result = result.replace("$key-$it", "{{ITEM:$it}}", ignoreCase = true)
                result = result.replace("$key$it", "{{ITEM:$it}}", ignoreCase = true)
            }
            result = result.replace(key, "{{ITEM:${sender.inventory.heldItemSlot + 1}}}", ignoreCase = true)
        }
        return result
    }

    override fun parseVariable(sender: Player, arg: String): ComponentText? {
        val item = sender.inventory.getItem(arg.toInt() - 1) ?: ItemStack(Material.AIR)
        if (MinecraftVersion.majorLegacy >= 12005 && item.isAir()) return null
        var newItem = if (compatible) {
            if (item.isAir()) ItemStack(Material.STONE) else buildItem(item) { material = Material.STONE }
        } else {
            var newItem = item.clone()
            HookPlugin.registry.filterIsInstance<HookDisplayItem>().forEach { element ->
                newItem = element.displayItem(newItem, sender)
            }
            newItem
        }
        val event = TrChatItemShowEvent(sender, newItem, compatible)
        event.call()
        newItem = event.item

        return cacheComponent.get(newItem) {
            if (ui) {
                val sha1 = computeAndCache(sender, item).let {
                    BukkitProxyManager.sendMessage(sender, arrayOf(
                        "ForwardMessage",
                        "ItemShow",
                        MinecraftVersion.minecraftVersion,
                        sender.name,
                        it.first,
                        it.second)
                    )
                    it.first
                }
                sender.getComponentFromLang("Function-Item-Show-Format-With-Hopper", newItem.amount, sha1) { type, i, part, proxySender ->
                    val component = if (part.isVariable && part.text == "item") {
                        item.getNameComponent(sender)
                    } else {
                        Components.text(part.text.translate(proxySender).replaceWithOrder(newItem.amount, sha1))
                    }
                    component.applyStyle(type, part, i, proxySender, newItem.amount, sha1).hoverItemFixed(newItem)
                }
            } else {
                sender.getComponentFromLang("Function-Item-Show-Format-New", newItem.amount) { type, i, part, proxySender ->
                    val component = if (part.isVariable && part.text == "item") {
                        item.getNameComponent(sender)
                    } else {
                        Components.text(part.text.translate(proxySender).replaceWithOrder(newItem.amount))
                    }
                    component.applyStyle(type, part, i, proxySender, newItem.amount).hoverItemFixed(newItem)
                }
            }
        }
    }

    override fun canUse(sender: Player): Boolean {
        return sender.passPermission(permission)
    }

    override fun checkCooldown(sender: Player, message: String): Boolean {
        if (enabled && keys.any { message.contains(it, ignoreCase = true) } && !sender.hasPermission("trchat.bypass.itemcd")) {
            val itemCooldown = sender.getCooldownLeft(CooldownType.ITEM_SHOW)
            if (itemCooldown > 0) {
                sender.sendLang("Cooldowns-Item-Show", itemCooldown / 1000)
                return false
            } else {
                sender.updateCooldown(CooldownType.ITEM_SHOW, cooldown.get())
            }
        }
        return true
    }

    fun computeAndCache(sender: Player, item: ItemStack): Pair<String, String> {
        TODO("1.21")
//        val sha1 = item.serializeToByteArray(zipped = false).encodeBase64().digest("sha-1")
//        if (cacheInventory.getIfPresent(sha1) != null) {
//            return sha1 to cacheInventory.getIfPresent(sha1)!!.serializeToByteArray().encodeBase64()
//        }
//        val inv = if (item.type.name.endsWith("SHULKER_BOX")) {
//            val blockStateMeta = item.itemMeta!! as BlockStateMeta
//            val shulkerBox = blockStateMeta.blockState as ShulkerBox
//            val shulkerInv = shulkerBox.inventory
//            buildMenu<PageableChest<ItemStack>>(sender.asLangText("Function-Item-Show-Title", sender.name)) {
//                rows(3)
//                slots((0..26).toList())
//                elements { (0..26).map { shulkerInv.getItem(it).replaceAir() } }
//                onGenerate { _, element, _, _ -> element }
//                onClick(lock = true)
//            }
//        } else {
//            buildMenu<Chest>(sender.asLangText("Function-Item-Show-Title", sender.name)) {
//                rows(3)
//                map(
//                    "xxxxxxxxx",
//                    "xxxxixxxx",
//                    "xxxxxxxxx"
//                )
//                set('x', XMaterial.BLACK_STAINED_GLASS_PANE) { name = "§f" }
//                set('i', item)
//                onClick(lock = true)
//            }
//        }
//        cacheInventory.put(sha1, inv)
//        return sha1 to inv.serializeToByteArray().encodeBase64()
    }

    @Suppress("Deprecation")
    private fun ItemStack.getNameComponent(player: Player): ComponentText {
        return if (!originName && itemMeta?.hasDisplayName() == true) {
            try {
                Components.empty().append(itemMeta!!.displayName()!!.toNative())
            } catch (_: Throwable) {
                try {
                    Components.empty().append(DefaultComponent(itemMeta!!.displayNameComponent.toList()))
                } catch (_: Throwable) {
                    Components.text(itemMeta!!.displayName)
                }
            }
        } else if (Folia.isFolia || MinecraftVersion.majorLegacy >= 12005) {
            toTranslatableComponentAdventure()
        } else {
            try {
                if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_15)) {
                    Components.translation(getLocaleKey().path)
                } else {
                    Components.text(getI18nName(player))
                }
            } catch (_: UnsupportedVersionException) {
                try {
                    // 玄学问题 https://github.com/TrPlugins/TrChat/issues/344
                    Components.translation(NMS.instance.getLocaleKey(this).path)
                } catch (_: Throwable) {
                    try {
                        Components.text(nmsProxy<NMSItem>().getKey(this))
                    } catch (_: Throwable) {
                        Components.text(type.name)
                    }
                }
            }
        }
    }

    private fun ItemStack?.replaceAir() = if (isAir()) AIR_ITEM else this

}