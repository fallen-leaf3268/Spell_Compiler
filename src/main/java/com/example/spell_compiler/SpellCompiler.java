package com.example.spell_compiler;

import com.example.spell_compiler.formula.FormulaConsumerNetwork;
import com.example.spell_compiler.formula.SpellFormulaDatapacks;
import com.example.spell_compiler.formula.SpellFormulaNetwork;
import com.example.spell_compiler.formula.SpellFormulaRuntime;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(SpellCompiler.MODID)
public final class SpellCompiler {
    public static final String MODID = "spell_compiler";
    public static final Logger LOGGER = LogManager.getLogger();

    public SpellCompiler() {
        SpellFormulaRuntime.initialize();
        SpellFormulaNetwork.initialize();
        FormulaConsumerNetwork.initialize();
        MinecraftForge.EVENT_BUS.addListener(SpellFormulaDatapacks::registerCommands);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::loadComplete);
        LOGGER.info("编译法术已加载。");
    }

    private void loadComplete(FMLLoadCompleteEvent event) {
        event.enqueueWork(SpellFormulaRuntime::initializeAddons);
    }
}
