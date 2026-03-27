package stsmodstarter.patches;

import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.shop.ShopScreen;
import stsmodstarter.bridge.BridgeActions;

@SpirePatch(clz = ShopScreen.class, method = "update")
public class ShopScreenPatch {
    // ?????? ShopScreen.update ??????????????????????
    public static void Prefix(ShopScreen __instance) {
        BridgeActions.tickPendingShopInput();
    }
}
