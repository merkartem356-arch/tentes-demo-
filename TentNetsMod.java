package com.tentnets;

import com.tentnets.client.ClientSetup;
import com.tentnets.net.NetEntity;
import com.tentnets.net.NetEvents;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(TentNetsMod.MODID)
public class TentNetsMod {
    public static final String MODID = "tentnets";

    public TentNetsMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModRegistry.ITEMS.register(modBus);
        ModRegistry.ENTITIES.register(modBus);
        modBus.addListener(this::addToCreativeTab);
        MinecraftForge.EVENT_BUS.addListener(NetEvents::onFall);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            for (NetEntity.Variant v : NetEntity.Variant.values()) {
                event.accept(ModRegistry.itemFor(v));
            }
        }
    }
}
