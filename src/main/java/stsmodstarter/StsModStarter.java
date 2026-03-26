package stsmodstarter;

import basemod.BaseMod;
import basemod.interfaces.PostInitializeSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import stsmodstarter.bridge.BridgePaths;
import stsmodstarter.bridge.GameStateBridge;

@SpireInitializer
public class StsModStarter implements PostInitializeSubscriber, PostUpdateSubscriber {
    public static final String MOD_ID = "stsmodstarter";
    private static final GameStateBridge BRIDGE = new GameStateBridge();

    public StsModStarter() {
        BaseMod.subscribe(this);
    }

    public static void initialize() {
        new StsModStarter();
        System.out.println("[" + MOD_ID + "] initialize");
    }

    @Override
    public void receivePostInitialize() {
        BridgePaths.ensureRuntimeDir();
        BRIDGE.writeStateSnapshot();
        System.out.println("[" + MOD_ID + "] receivePostInitialize");
        System.out.println("[" + MOD_ID + "] runtime dir: " + BridgePaths.runtimeDir());
    }

    @Override
    public void receivePostUpdate() {
        BRIDGE.tick();
    }
}