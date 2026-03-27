package stsmodstarter.bridge;

import basemod.ReflectionHacks;
import com.badlogic.gdx.Gdx;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.blights.AbstractBlight;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.OverlayMenu;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.RoomEventDialog;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rewards.chests.AbstractChest;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.CampfireUI;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.charSelect.CharacterOption;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuPanelButton;
import com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen;
import com.megacrit.cardcrawl.screens.mainMenu.MenuButton;
import com.megacrit.cardcrawl.screens.DungeonMapScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.screens.select.BossRelicSelectScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.panels.PotionPopUp;
import com.megacrit.cardcrawl.ui.buttons.CancelButton;
import com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.ConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.EndTurnButton;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.ui.buttons.SkipCardButton;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;

import java.lang.reflect.Field;
import java.util.ArrayList;

public final class BridgeActions {
    // play_card is modeled as a small multi-frame state machine: pick card, drop card, then target if needed.
    private static final int PENDING_INPUT_TIMEOUT_TICKS = 180;
    private static PendingPlayCard pendingPlayCard;
    private static PendingPotionUse pendingPotionUse;
    private static PendingShopClick pendingShopClick;
    private static PendingEventClick pendingEventClick;

    private BridgeActions() {
    }

    public static String playCard(BridgeProtocol.CommandEnvelope command) {
        requireContext("combat");
        if (pendingPlayCard != null) {
            throw new IllegalStateException("Another play_card input sequence is still in progress");
        }
        AbstractPlayer player = AbstractDungeon.player;
        AbstractCard card = resolveCardFromGroup(player.hand, command.card_uuid, command.card_index, "hand");
        AbstractMonster target = resolveTarget(card, command.target_index);
        if (!card.canUse(player, target)) {
            throw new IllegalStateException("Card cannot be used right now: " + card.name);
        }
        pendingPlayCard = PendingPlayCard.create(card, target, requiresTarget(card));
        return target == null
                ? "Queued play_card input: " + card.name
                : "Queued play_card input: " + card.name + " -> " + target.name;
    }

    public static void tickPendingActions() {
        tickPendingPlayCard();
        tickPendingPotionUse();
        tickPendingEventClick();
    }

    // Clear any bridge-owned pending inputs before a new run starts.
    public static void resetPendingActions() {
        pendingPlayCard = null;
        pendingPotionUse = null;
        pendingShopClick = null;
        pendingEventClick = null;
    }

    public static void tickPendingShopInput() {
        tickPendingShopClick();
    }

    private static void tickPendingPlayCard() {
        PendingPlayCard pending = pendingPlayCard;
        if (pending == null) {
            return;
        }
        if (!AbstractDungeon.isPlayerInDungeon() || AbstractDungeon.player == null) {
            pendingPlayCard = null;
            return;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
            pendingPlayCard = null;
            return;
        }

        pending.remainingTicks--;
        if (pending.remainingTicks <= 0) {
            pendingPlayCard = null;
            return;
        }

        AbstractPlayer player = AbstractDungeon.player;
        AbstractCard card = findCardInHand(pending.cardUuid);
        switch (pending.phase) {
            case SELECT_CARD:
                if (card == null) {
                    pendingPlayCard = null;
                    return;
                }
                queueCardSelection(card);
                pending.phase = PlayCardPhase.WAIT_FOR_DRAG;
                return;
            case WAIT_FOR_DRAG:
                if (card == null) {
                    pendingPlayCard = null;
                    return;
                }
                if (!isDraggingPendingCard(player, pending.cardUuid)) {
                    queueCardSelection(card);
                    return;
                }
                queueDropZoneRelease(player);
                pending.phase = pending.requiresTarget ? PlayCardPhase.WAIT_FOR_TARGET_MODE : PlayCardPhase.WAIT_FOR_RESOLUTION;
                return;
            case WAIT_FOR_TARGET_MODE:
                if (card == null) {
                    pendingPlayCard = null;
                    return;
                }
                if (!player.inSingleTargetMode || player.hoveredCard == null || !pending.cardUuid.equals(player.hoveredCard.uuid.toString())) {
                    if (isDraggingPendingCard(player, pending.cardUuid)) {
                        queueDropZoneRelease(player);
                    }
                    return;
                }
                AbstractMonster target = resolvePendingTarget(pending.targetIndex);
                if (target == null) {
                    pendingPlayCard = null;
                    return;
                }
                queueTargetRelease(target);
                pending.phase = PlayCardPhase.WAIT_FOR_RESOLUTION;
                return;
            case WAIT_FOR_RESOLUTION:
                if (pending.requiresTarget && player.inSingleTargetMode) {
                    AbstractMonster waitingTarget = resolvePendingTarget(pending.targetIndex);
                    if (waitingTarget != null) {
                        queueTargetRelease(waitingTarget);
                        return;
                    }
                }
                if (card == null || (!player.isDraggingCard && !player.inSingleTargetMode)) {
                    pendingPlayCard = null;
                }
                return;
            default:
                pendingPlayCard = null;
        }
    }

    private static void tickPendingPotionUse() {
        PendingPotionUse pending = pendingPotionUse;
        if (pending == null) {
            return;
        }
        if (!AbstractDungeon.isPlayerInDungeon() || AbstractDungeon.player == null) {
            pendingPotionUse = null;
            return;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.phase != AbstractRoom.RoomPhase.COMBAT) {
            pendingPotionUse = null;
            return;
        }

        pending.remainingTicks--;
        if (pending.remainingTicks <= 0) {
            pendingPotionUse = null;
            return;
        }

        AbstractPotion potion = resolvePendingPotion(pending.slot);
        PotionPopUp popup = getPotionPopup();
        switch (pending.phase) {
            case PRESS_POTION_SLOT:
                if (potion == null) {
                    pendingPotionUse = null;
                    return;
                }
                queuePotionPress(potion);
                pending.phase = PotionUsePhase.RELEASE_POTION_SLOT;
                return;
            case RELEASE_POTION_SLOT:
                if (potion == null) {
                    pendingPotionUse = null;
                    return;
                }
                queuePotionRelease(potion);
                pending.phase = PotionUsePhase.WAIT_FOR_POPUP;
                return;
            case WAIT_FOR_POPUP:
                if (potion == null || potion.discarded || !potion.isObtained) {
                    pendingPotionUse = null;
                    return;
                }
                if (isPotionPopupVisible(popup)) {
                    queuePotionPopupPress(popup);
                    pending.phase = PotionUsePhase.RELEASE_POPUP_CONFIRM;
                }
                return;
            case RELEASE_POPUP_CONFIRM:
                if (potion == null) {
                    pendingPotionUse = null;
                    return;
                }
                if (isPotionPopupVisible(popup)) {
                    queuePotionPopupRelease(popup);
                    pending.phase = pending.requiresTarget ? PotionUsePhase.WAIT_FOR_TARGET_MODE : PotionUsePhase.WAIT_FOR_RESOLUTION;
                }
                return;
            case WAIT_FOR_TARGET_MODE:
                if (isPotionTargetingActive()) {
                    if (pending.targetSelf) {
                        queuePlayerTargetRelease();
                    } else {
                        AbstractMonster target = resolvePendingTarget(pending.targetIndex);
                        if (target == null) {
                            pendingPotionUse = null;
                            return;
                        }
                        queueTargetRelease(target);
                    }
                    pending.phase = PotionUsePhase.WAIT_FOR_RESOLUTION;
                    return;
                }
                if (potion == null || potion.discarded || !potion.isObtained) {
                    pendingPotionUse = null;
                }
                return;
            case WAIT_FOR_RESOLUTION:
                if (pending.requiresTarget && isPotionTargetingActive()) {
                    if (pending.targetSelf) {
                        queuePlayerTargetRelease();
                        return;
                    }
                    AbstractMonster waitingTarget = resolvePendingTarget(pending.targetIndex);
                    if (waitingTarget != null) {
                        queueTargetRelease(waitingTarget);
                        return;
                    }
                }
                if (potion == null || potion.discarded || !potion.isObtained) {
                    pendingPotionUse = null;
                }
                return;
            default:
                pendingPotionUse = null;
        }
    }

    private static void tickPendingEventClick() {
        PendingEventClick pending = pendingEventClick;
        if (pending == null) {
            return;
        }
        pending.remainingTicks--;
        if (pending.remainingTicks <= 0) {
            pendingEventClick = null;
            return;
        }
        switch (pending.phase) {
            case PRESS:
                queueEventPress(pending.hitbox);
                pending.phase = EventClickPhase.RELEASE;
                return;
            case RELEASE:
                LargeDialogOptionButton activeButton = resolveActiveEventButton(pending.optionIndex);
                if (activeButton != null) {
                    queueEventRelease(activeButton.hb);
                } else {
                    queueLeftRelease();
                }
                pendingEventClick = null;
                return;
            default:
                pendingEventClick = null;
        }
    }

    private static void tickPendingShopClick() {
        PendingShopClick pending = pendingShopClick;
        if (pending == null) {
            return;
        }
        if (!AbstractDungeon.isPlayerInDungeon()) {
            pendingShopClick = null;
            return;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        boolean inShopRoom = room != null && room.getClass().getSimpleName().contains("ShopRoom");
        if (!inShopRoom || AbstractDungeon.shopScreen == null) {
            pendingShopClick = null;
            return;
        }
        pending.remainingTicks--;
        if (pending.remainingTicks <= 0) {
            pendingShopClick = null;
            return;
        }
        switch (pending.phase) {
            case PRESS:
                if (pending.hitbox != null) {
                    queueShopPress(pending.hitbox);
                } else {
                    queueShopPress(pending.x, pending.y);
                }
                pending.phase = ShopClickPhase.RELEASE;
                return;
            case RELEASE:
                if (pending.hitbox != null) {
                    queueShopRelease(pending.hitbox);
                } else {
                    queueShopRelease(pending.x, pending.y);
                }
                pendingShopClick = null;
                return;
            default:
                pendingShopClick = null;
        }
    }

    public static String endTurn() {
        requireContext("combat");
        OverlayMenu overlayMenu = AbstractDungeon.overlayMenu;
        if (overlayMenu == null || overlayMenu.endTurnButton == null) {
            throw new IllegalStateException("End turn button is not available");
        }
        Hitbox hitbox = (Hitbox) ReflectionHacks.getPrivate(overlayMenu.endTurnButton, EndTurnButton.class, "hb");
        click(hitbox);
        return "End turn button clicked";
    }

    public static String clickProceed() {
        ProceedButton button = AbstractDungeon.overlayMenu == null ? null : AbstractDungeon.overlayMenu.proceedButton;
        if (button == null) {
            throw new IllegalStateException("Proceed button is not available");
        }
        Hitbox hitbox = (Hitbox) ReflectionHacks.getPrivate(button, ProceedButton.class, "hb");
        click(hitbox);
        return "Proceed button clicked";
    }

    public static String claimRoomReward(BridgeProtocol.CommandEnvelope command) {
        ArrayList<RewardItem> rewards = getVisibleRoomRewards();
        if (rewards == null || rewards.isEmpty()) {
            throw new IllegalStateException("No room rewards are available");
        }
        int rewardIndex = requireInt(command.reward_index, "reward_index");
        if (rewardIndex < 0 || rewardIndex >= rewards.size()) {
            throw new IllegalArgumentException("reward_index out of range: " + rewardIndex);
        }
        RewardItem reward = rewards.get(rewardIndex);
        if (reward.isDone) {
            throw new IllegalStateException("Reward is already claimed: " + rewardIndex);
        }
        click(reward.hb);
        return "Clicked room reward " + rewardIndex;
    }

    // 婵犵數濮烽弫鍛婄箾閳ь剚绻涙担鍐叉搐绾惧湱鎲搁悧鍫濈瑲闁稿顑夐弻锟犲炊閳轰焦鐎鹃梺鍛婄懃缁绘﹢寮婚悢鐓庣畾闁哄鏅濋幘缁樼厱濠电姴瀚崢鎾煛瀹€瀣М闁诡喓鍨藉畷銊︾節閸曨偄娈ュ┑鐘愁問閸ｎ垳寰婃禒瀣櫇妞ゅ繐鐗嗛拑鐔兼煥濠靛棭妲搁幆鐔兼⒑闂堟侗妲堕柛搴ｅ劋鐎靛ジ鍩€椤掑嫭鈷掑ù锝勮閻掗箖鎮跺鐓庝喊鐎规洘绻傞悾婵嬪礋閸偅娅旈梻浣瑰缁诲倿藝椤栫偛纭€闁规儼濮ら悡鍐煢濡警妲规い銉у仱閺岋綁顢橀悤浣圭杹濠殿喖锕ュ钘夌暦閵婏妇绡€闁告洦鍘鹃崢鎰版⒒娴ｈ銇熼柛鎾寸懇婵″墎绮欏▎鎯ф闂佸搫琚崕娲极閸愵喗鐓ラ柡鍐ㄥ€婚幗鍌涗繆椤愩垹鏆欐い顏勫暣婵¤埖鎯旈垾鑼跺焻闂備礁鎲￠敃銏㈢不閺嶎厼绠栭柣鎴ｆ缁犳盯鏌ｅΔ鈧悧蹇涘储閽樺娓婚柕鍫濇噽缁犱即鎮楀鍗炲幋鐎规洘娲濈粻娑樷槈濞嗘垵骞堥梻浣哥枃濡椼劑鎳楅崼鏇熷€块柟闂寸劍閻撴瑦銇勯弽銊ㄥ闁哄棴绲块埀顒冾潐濞叉粓宕伴弽顓溾偓浣糕槈濡粎鍠庨悾鈩冿紣娴ｅ壊妫滈梻浣烘嚀閸氬鎮鹃鍫濆瀭婵炲樊浜滅壕鍧楁煟閺冨洤浜归柛娆愭崌閺屾盯濡烽幋婵嗩仾鐎规洖鍟块—鍐Χ閸涱垳顔掓繛瀛樼矊閻栫厧顕ｆ繝姘у璺侯儏娴犳儳顪冮妶鍡欏缂佸鍨甸埥澶庮槾缂佽鲸鎸婚幏鍛鐎ｎ亝鎳欑紓鍌欐祰椤曆呪偓姘煎幘缁顓兼径濠勵啇婵炶揪缍€閸婂€燁樄闁哄本绋戦埥澶愬础閻愯尙顔愮紓鍌欑窔椤ゅ倿宕ｉ崘顭戞綎婵炲樊浜滄导鐘绘煕閺囥劌浜藉ù鐓庣焸濮婃椽骞栭悙鎻掝瀳闂佺粯鐗炴竟鍫ユ儗妤ｅ啯鈷戠紒瀣濠€鎵磼鐎ｎ偄鐏╂繛鍡愬灲閹瑩鎮滃Ο鐓庡箰闁诲骸绠嶉崕閬嶅疮椤愶絼绻嗛柤娴嬫櫇绾惧ジ鏌熺紒妯轰刊闁绘挸銈搁弻锛勪沪閸撗岀伇缂備胶濮电粙鎾诲焵椤掑﹦绉靛ù婊勭矋閹便劑濮€閻橆偅鏂€闂佺粯鍔樼亸娆愭櫠濞戙垺鐓曢柕濞垮劤娴犮垽鏌ｉ敐鍥у幋闁轰焦鎹囬幃鈺呮惞椤愶絿褰ㄩ梻鍌欑閹测€趁洪敃鍌氱；闁圭儤顨呯痪褔鏌ｉ幋鐑嗙劷缂佲檧鍋撻梻鍌氬€搁悧濠勭矙閹达箑鐒垫い鎺嗗亾缂傚秴锕獮鍐樄鐎殿喗鎸抽幃銏㈢矙閸喕绱熼梻鍌欑劍閸庡啿霉濮樿泛纾诲ù锝呮贡椤╁弶绻濇繝鍌氼伌婵炲牅绮欓弻锝夊箛椤栨氨姣㈠┑顔角滈崝鎴﹀蓟?
    private static ArrayList<RewardItem> getVisibleRoomRewards() {
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD
                && AbstractDungeon.combatRewardScreen != null
                && AbstractDungeon.combatRewardScreen.rewards != null) {
            return AbstractDungeon.combatRewardScreen.rewards;
        }
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null) {
            return null;
        }
        return room.rewards;
    }

    public static String chooseCardReward(BridgeProtocol.CommandEnvelope command) {
        if (AbstractDungeon.cardRewardScreen == null || AbstractDungeon.cardRewardScreen.rewardGroup == null) {
            throw new IllegalStateException("Card reward screen is not open");
        }
        int rewardIndex = requireInt(command.reward_index, "reward_index");
        if (rewardIndex < 0 || rewardIndex >= AbstractDungeon.cardRewardScreen.rewardGroup.size()) {
            throw new IllegalArgumentException("reward_index out of range: " + rewardIndex);
        }
        AbstractCard card = AbstractDungeon.cardRewardScreen.rewardGroup.get(rewardIndex);
        click(card.hb);
        return "Clicked card reward: " + card.name;
    }

    public static String skipCardReward() {
        if (AbstractDungeon.cardRewardScreen == null) {
            throw new IllegalStateException("Card reward screen is not open");
        }
        SkipCardButton button = (SkipCardButton) ReflectionHacks.getPrivate(AbstractDungeon.cardRewardScreen, CardRewardScreen.class, "skipButton");
        if (button == null) {
            throw new IllegalStateException("Skip reward button is not available");
        }
        click(button.hb);
        return "Skip reward button clicked";
    }

    public static String chooseEventOption(BridgeProtocol.CommandEnvelope command) {
        int optionIndex = requireInt(command.option_index, "option_index");
        LargeDialogOptionButton button = resolveEventButton(optionIndex);
        if (button.isDisabled) {
            throw new IllegalStateException("Event option is disabled: " + command.option_index);
        }
        clickEventButton(button.hb);
        return "Clicked event option " + optionIndex;
    }

    public static String chooseCampfireOption(BridgeProtocol.CommandEnvelope command) {
        CampfireUI campfireUI = requireCampfireUi();
        @SuppressWarnings("unchecked")
        ArrayList<AbstractCampfireOption> buttons = (ArrayList<AbstractCampfireOption>) ReflectionHacks.getPrivate(campfireUI, CampfireUI.class, "buttons");
        int optionIndex = requireInt(command.option_index, "option_index");
        if (buttons == null || optionIndex < 0 || optionIndex >= buttons.size()) {
            throw new IllegalArgumentException("option_index out of range: " + optionIndex);
        }
        AbstractCampfireOption option = buttons.get(optionIndex);
        if (!option.usable) {
            throw new IllegalStateException("Campfire option is disabled");
        }
        click(option.hb);
        return "Clicked campfire option " + optionIndex;
    }

    public static String chooseMapNode(BridgeProtocol.CommandEnvelope command) {
        int x = requireInt(command.x, "x");
        int y = requireInt(command.y, "y");
        MapRoomNode node = findMapNode(x, y);
        if (node == null) {
            throw new IllegalArgumentException("Map node not found: (" + x + ", " + y + ")");
        }
        if (!isMapNodeSelectable(node)) {
            throw new IllegalStateException("Map node is not selectable right now: (" + x + ", " + y + ")");
        }
        moveCursorToHitbox(node.hb);
        node.hb.hovered = true;
        node.hb.justHovered = true;
        DungeonMapScreen mapScreen = AbstractDungeon.dungeonMapScreen;
        if (mapScreen == null) {
            throw new IllegalStateException("Map screen is not available");
        }
        queueMapClick(mapScreen, node.hb);
        return "Queued map click for node (" + x + ", " + y + ")";
    }

    public static String chooseMainMenuOption(BridgeProtocol.CommandEnvelope command) {
        MainMenuScreen mainMenuScreen = requireMainMenuScreen();
        int optionIndex = requireInt(command.option_index, "option_index");
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.MAIN_MENU) {
            if (mainMenuScreen.buttons == null) {
                throw new IllegalStateException("Main menu buttons are not available");
            }
            MenuButton button = resolveMenuButton(mainMenuScreen, optionIndex);
            click(button.hb);
            return "Clicked main menu option " + button.result.name();
        }
        if (mainMenuScreen.screen == MainMenuScreen.CurScreen.PANEL_MENU) {
            MainMenuPanelButton button = resolvePanelButton(mainMenuScreen, optionIndex);
            click(button.hb);
            return "Clicked panel menu option " + readPanelButtonId(button, optionIndex);
        }
        throw new IllegalStateException("Main menu option buttons are not available on screen: " + mainMenuScreen.screen.name());
    }

    public static String selectCharacter(BridgeProtocol.CommandEnvelope command) {
        MainMenuScreen mainMenuScreen = requireMainMenuScreen();
        if (mainMenuScreen.screen != MainMenuScreen.CurScreen.CHAR_SELECT || mainMenuScreen.charSelectScreen == null) {
            throw new IllegalStateException("Character select screen is not active");
        }
        CharacterOption option = resolveCharacterOption(mainMenuScreen, requireInt(command.option_index, "option_index"));
        if (option.locked) {
            throw new IllegalStateException("Character is locked: " + option.name);
        }
        click(option.hb);
        return "Clicked character option: " + option.name;
    }

    public static String chooseBossReward(BridgeProtocol.CommandEnvelope command) {
        BossRelicSelectScreen screen = requireBossRewardScreen();
        int optionIndex = requireInt(command.option_index, "option_index");
        Hitbox hitbox = resolveBossRewardHitbox(screen, optionIndex);
        click(hitbox);
        return "Clicked boss reward option " + optionIndex;
    }

    public static String openTreasureChest() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || !room.getClass().getSimpleName().contains("TreasureRoom")) {
            throw new IllegalStateException("Current room is not a treasure room");
        }
        Object chestObj = ReflectionHacks.getPrivate(room, room.getClass(), "chest");
        if (!(chestObj instanceof AbstractChest)) {
            throw new IllegalStateException("Treasure chest is not available");
        }
        AbstractChest chest = (AbstractChest) chestObj;
        if (chest.isOpen) {
            throw new IllegalStateException("Treasure chest is already open");
        }
        Hitbox chestHitbox = (Hitbox) ReflectionHacks.getPrivate(chest, AbstractChest.class, "hb");
        // 闂傚倷娴囬褍顫濋敃鍌︾稏濠㈣泛鏈畷鏌ユ煕閺囥劌鐏遍柡浣革躬閺屾盯顢曢妶鍛亖闂佸磭绮ú鐔煎蓟閿濆绫嶉柍褜鍓欏嵄闁圭儤鍨熼弸鏍煛鐏炶鍔滈柣鎾存礃娣囧﹪顢涘鍐ㄤ粯闂佸搫顑嗗Λ鍐蓟?hitbox 闂傚倸鍊烽懗鍓佸垝椤栫偛绀夋俊銈呮噹缁犵娀鏌熼幑鎰靛殭闁告俺顫夐妵鍕即濡も偓娴滈箖姊烘潪鎵槮缂佸鍩栫粋鎺楁晝閸屾稑娈愰梺鍐叉惈閸燁垶宕伴幒妤佲拻濞达絽鎼崝锕傛煛閸涱喚娲撮柟顔芥そ婵℃瓕顦抽柡鍡樼矒閺岀喓绱掗姀鐘崇亪闂佹椿鍘介〃濠囧蓟閻旇　鍋撻悽娈跨劸濞寸媴绠撻弻鈩冨緞婵犲嫮楔濠殿喖锕ュ浠嬨€佸Δ浣虹懝闁搞儯鍔庨弳顓炩攽閻橆偅濯伴柛鎰ㄦ櫅娴犲瓨绻?
        click(chestHitbox);
        return "Clicked treasure chest";
    }

    public static String openShop() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || !room.getClass().getSimpleName().contains("ShopRoom")) {
            throw new IllegalStateException("Current room is not a shop room");
        }
        Object merchantObj = ReflectionHacks.getPrivate(room, room.getClass(), "merchant");
        if (merchantObj == null) {
            throw new IllegalStateException("Merchant is not available");
        }
        Hitbox merchantHitbox = (Hitbox) ReflectionHacks.getPrivate(merchantObj, merchantObj.getClass(), "hb");
        // Merchant.update ??? hovered + justClickedLeft??????????????
        click(merchantHitbox);
        return "Clicked merchant";
    }
    public static String buyShopCard(BridgeProtocol.CommandEnvelope command) {
        ShopScreen shopScreen = requireShopScreen();
        if (pendingShopClick != null) {
            throw new IllegalStateException("Another shop click is still in progress");
        }
        AbstractCard card = resolveShopCard(shopScreen, command.card_uuid, command.card_index);
        // ????????? -> ??????? hitbox ????? clicked ???????
        pendingShopClick = PendingShopClick.forHitbox(card.hb);
        return "Queued shop card click: " + card.name;
    }

    public static String buyShopRelic(BridgeProtocol.CommandEnvelope command) {
        if (pendingShopClick != null) {
            throw new IllegalStateException("Another shop click is still in progress");
        }
        StoreRelic relic = resolveShopRelic(requireInt(command.option_index, "option_index"));
        if (relic.isPurchased) {
            throw new IllegalStateException("Shop relic is already purchased");
        }
        pendingShopClick = PendingShopClick.forHitbox(relic.relic.hb);
        return "Queued shop relic click: " + relic.relic.name;
    }

    public static String buyShopPotion(BridgeProtocol.CommandEnvelope command) {
        if (pendingShopClick != null) {
            throw new IllegalStateException("Another shop click is still in progress");
        }
        StorePotion potion = resolveShopPotion(requireInt(command.option_index, "option_index"));
        if (potion.isPurchased) {
            throw new IllegalStateException("Shop potion is already purchased");
        }
        pendingShopClick = PendingShopClick.forHitbox(potion.potion.hb);
        return "Queued shop potion click: " + potion.potion.name;
    }

    public static String buyShopPurge() {
        ShopScreen shopScreen = requireShopScreen();
        if (pendingShopClick != null) {
            throw new IllegalStateException("Another shop click is still in progress");
        }
        if (!shopScreen.purgeAvailable) {
            throw new IllegalStateException("Shop purge is not available");
        }
        pendingShopClick = PendingShopClick.forPoint(readShopPurgeX(shopScreen), readShopPurgeY(shopScreen));
        return "Queued shop purge click";
    }

    // 鎴樻枟鑽按鍙ā鎷熻緭鍏ワ紝涓嶇洿鎺ヨ皟鐢?use()锛涚洰鏍囪嵂姘翠細绛夊緟鍘熺増杩涘叆閫夋€ā寮忋€?
    public static String usePotion(BridgeProtocol.CommandEnvelope command) {
        requireContext("combat");
        if (pendingPotionUse != null) {
            throw new IllegalStateException("Another potion input sequence is still in progress");
        }
        AbstractPotion potion = resolveCombatPotion(requireInt(command.option_index, "option_index"));
        if (!potion.canUse()) {
            throw new IllegalStateException("Potion cannot be used right now: " + potion.name);
        }
        boolean targetSelf = potion.targetRequired && (command.target_index == null || command.target_index.intValue() < 0);
        AbstractMonster target = potion.targetRequired && !targetSelf
                ? resolveMonsterTarget(requireInt(command.target_index, "target_index"))
                : null;
        pendingPotionUse = PendingPotionUse.create(potion, target, potion.targetRequired, targetSelf);
        if (targetSelf) {
            return "Queued potion use: " + potion.name + " -> self";
        }
        return target == null
                ? "Queued potion use: " + potion.name
                : "Queued potion use: " + potion.name + " -> " + target.name;
    }

    public static String selectHandCard(BridgeProtocol.CommandEnvelope command) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT) {
            throw new IllegalStateException("Hand selection screen is not active");
        }
        CardGroup hand = (CardGroup) ReflectionHacks.getPrivate(AbstractDungeon.handCardSelectScreen, HandCardSelectScreen.class, "hand");
        AbstractCard card = resolveCardFromGroup(hand, command.card_uuid, command.card_index, "hand_select");
        clickCardHitbox(card.hb);
        return "Selected hand card: " + card.name;
    }

    public static String selectGridCard(BridgeProtocol.CommandEnvelope command) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID || AbstractDungeon.gridSelectScreen.targetGroup == null) {
            throw new IllegalStateException("Grid selection screen is not active");
        }
        AbstractCard card = resolveCardFromGroup(AbstractDungeon.gridSelectScreen.targetGroup, command.card_uuid, command.card_index, "grid_select");
        // Grid selection also needs hoveredCard aligned, not just a raw hitbox click.
        clickGridSelectCard(card);
        return "Selected grid card: " + card.name;
    }
    public static String confirmCurrentScreen() {
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.HAND_SELECT) {
            CardSelectConfirmButton button = AbstractDungeon.handCardSelectScreen.button;
            if (button == null || button.isDisabled) {
                throw new IllegalStateException("Hand select confirm button is disabled");
            }
            click(button.hb);
            return "Confirmed hand selection";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID) {
            GridSelectConfirmButton button = AbstractDungeon.gridSelectScreen.confirmButton;
            if (button == null || button.isDisabled) {
                throw new IllegalStateException("Grid select confirm button is disabled");
            }
            click(button.hb);
            return "Confirmed grid selection";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.BOSS_REWARD && AbstractDungeon.bossRelicScreen != null) {
            ConfirmButton button = AbstractDungeon.bossRelicScreen.confirmButton;
            if (button == null || button.isDisabled) {
                throw new IllegalStateException("Boss reward confirm button is disabled");
            }
            click(button.hb);
            return "Confirmed boss reward";
        }
        MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
        if (mainMenuScreen != null
                && mainMenuScreen.screen == MainMenuScreen.CurScreen.ABANDON_CONFIRM
                && mainMenuScreen.abandonPopup != null
                && mainMenuScreen.abandonPopup.shown) {
            if (mainMenuScreen.abandonPopup.yesHb == null) {
                throw new IllegalStateException("Abandon confirm button is unavailable");
            }
            click(mainMenuScreen.abandonPopup.yesHb);
            return "Confirmed abandon run";
        }
        if (mainMenuScreen != null
                && mainMenuScreen.screen == MainMenuScreen.CurScreen.CHAR_SELECT
                && mainMenuScreen.charSelectScreen != null) {
            ConfirmButton button = mainMenuScreen.charSelectScreen.confirmButton;
            if (button == null || button.isDisabled) {
                throw new IllegalStateException("Character select confirm button is disabled");
            }
            click(button.hb);
            return "Confirmed character selection";
        }
        CampfireUI campfireUI = tryCampfireUi();
        if (campfireUI != null) {
            ConfirmButton button = campfireUI.confirmButton;
            if (button != null && !button.isDisabled) {
                click(button.hb);
                return "Confirmed campfire selection";
            }
        }
        throw new IllegalStateException("No confirm action is available");
    }

    public static String cancelCurrentScreen() {
        MainMenuScreen mainMenuScreen = CardCrawlGame.mainMenuScreen;
        if (mainMenuScreen != null
                && mainMenuScreen.screen == MainMenuScreen.CurScreen.ABANDON_CONFIRM
                && mainMenuScreen.abandonPopup != null
                && mainMenuScreen.abandonPopup.shown) {
            if (mainMenuScreen.abandonPopup.noHb == null) {
                throw new IllegalStateException("Abandon cancel button is unavailable");
            }
            click(mainMenuScreen.abandonPopup.noHb);
            return "Cancelled abandon run";
        }
        if (mainMenuScreen != null
                && mainMenuScreen.screen == MainMenuScreen.CurScreen.PANEL_MENU
                && mainMenuScreen.panelScreen != null
                && mainMenuScreen.panelScreen.button != null
                && !mainMenuScreen.panelScreen.button.isHidden) {
            click(mainMenuScreen.panelScreen.button.hb);
            return "Cancelled panel menu";
        }
        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP
                && AbstractDungeon.overlayMenu != null
                && AbstractDungeon.overlayMenu.cancelButton != null
                && !AbstractDungeon.overlayMenu.cancelButton.isHidden) {
            CancelButton button = AbstractDungeon.overlayMenu.cancelButton;
            click(button.hb);
            return "Closed shop screen";
        }
        throw new IllegalStateException("No cancel action is available");
    }

    private static void requireContext(String expected) {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        String actual = room != null && room.phase == AbstractRoom.RoomPhase.COMBAT ? "combat" : "room";
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Expected context '" + expected + "' but was '" + actual + "'");
        }
    }

    private static MainMenuScreen requireMainMenuScreen() {
        if (CardCrawlGame.mainMenuScreen == null) {
            throw new IllegalStateException("Main menu screen is not available");
        }
        return CardCrawlGame.mainMenuScreen;
    }

    private static BossRelicSelectScreen requireBossRewardScreen() {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.BOSS_REWARD || AbstractDungeon.bossRelicScreen == null) {
            throw new IllegalStateException("Boss reward screen is not active");
        }
        return AbstractDungeon.bossRelicScreen;
    }

    private static MenuButton resolveMenuButton(MainMenuScreen mainMenuScreen, int optionIndex) {
        if (mainMenuScreen.buttons == null || optionIndex < 0 || optionIndex >= mainMenuScreen.buttons.size()) {
            throw new IllegalArgumentException("option_index out of range: " + optionIndex);
        }
        return mainMenuScreen.buttons.get(optionIndex);
    }

    private static MainMenuPanelButton resolvePanelButton(MainMenuScreen mainMenuScreen, int optionIndex) {
        if (mainMenuScreen.panelScreen == null
                || mainMenuScreen.panelScreen.panels == null
                || optionIndex < 0
                || optionIndex >= mainMenuScreen.panelScreen.panels.size()) {
            throw new IllegalArgumentException("option_index out of range: " + optionIndex);
        }
        return mainMenuScreen.panelScreen.panels.get(optionIndex);
    }

    private static String readPanelButtonId(MainMenuPanelButton button, int optionIndex) {
        try {
            Object result = ReflectionHacks.getPrivate(button, MainMenuPanelButton.class, "result");
            if (result instanceof Enum<?>) {
                return ((Enum<?>) result).name();
            }
        } catch (RuntimeException ignored) {
        }
        return "panel_option_" + optionIndex;
    }

    private static CharacterOption resolveCharacterOption(MainMenuScreen mainMenuScreen, int optionIndex) {
        if (mainMenuScreen.charSelectScreen == null
                || mainMenuScreen.charSelectScreen.options == null
                || optionIndex < 0
                || optionIndex >= mainMenuScreen.charSelectScreen.options.size()) {
            throw new IllegalArgumentException("option_index out of range: " + optionIndex);
        }
        return mainMenuScreen.charSelectScreen.options.get(optionIndex);
    }

    private static Hitbox resolveBossRewardHitbox(BossRelicSelectScreen screen, int optionIndex) {
        int cursor = 0;
        if (screen.relics != null) {
            for (AbstractRelic relic : screen.relics) {
                if (relic == null) {
                    continue;
                }
                if (cursor == optionIndex) {
                    return relic.hb;
                }
                cursor++;
            }
        }
        if (screen.blights != null) {
            for (AbstractBlight blight : screen.blights) {
                if (blight == null) {
                    continue;
                }
                if (cursor == optionIndex) {
                    return blight.hb;
                }
                cursor++;
            }
        }
        throw new IllegalArgumentException("option_index out of range: " + optionIndex);
    }
    // Generic UI click helper used by rewards, menu buttons, confirm buttons, and shop items.
    private static void click(Hitbox hitbox) {
        if (hitbox == null) {
            throw new IllegalStateException("Target hitbox is missing");
        }
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = true;
        InputHelper.justClickedLeft = true;
        InputHelper.justReleasedClickLeft = false;
        InputHelper.isMouseDown = false;
    }

    private static void clickEventButton(Hitbox hitbox) {
        if (hitbox == null) {
            throw new IllegalStateException("Event option hitbox is missing");
        }
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = true;
        InputHelper.justReleasedClickLeft = true;
        InputHelper.justClickedLeft = true;
        InputHelper.isMouseDown = false;
    }

    private static void clickCardHitbox(Hitbox hitbox) {
        click(hitbox);
    }

    private static void clickGridSelectCard(AbstractCard card) {
        if (card == null) {
            throw new IllegalStateException("Grid card is missing");
        }
        setPrivateField(AbstractDungeon.gridSelectScreen, GridCardSelectScreen.class, "hoveredCard", card);
        clickCardHitbox(card.hb);
    }

    private static int readShopPurgeX(ShopScreen shopScreen) {
        Object rawX = getPrivateField(shopScreen, ShopScreen.class, "purgeCardX");
        if (!(rawX instanceof Float)) {
            throw new IllegalStateException("Shop purge coordinates are unavailable");
        }
        return Math.round(((Float) rawX).floatValue());
    }

    private static int readShopPurgeY(ShopScreen shopScreen) {
        Object rawY = getPrivateField(shopScreen, ShopScreen.class, "purgeCardY");
        if (!(rawY instanceof Float)) {
            throw new IllegalStateException("Shop purge coordinates are unavailable");
        }
        return Math.round(((Float) rawY).floatValue());
    }
    private static void queueLeftPress() {
        InputHelper.justClickedLeft = true;
        InputHelper.justReleasedClickLeft = false;
        InputHelper.isMouseDown = true;
    }

    private static void queueLeftRelease() {
        InputHelper.justClickedLeft = false;
        InputHelper.justReleasedClickLeft = true;
        InputHelper.isMouseDown = false;
    }
    // Step 1: hover the hand card and press the left mouse button to start dragging.
    private static void queueCardSelection(AbstractCard card) {
        moveCursorToHitbox(card.hb);
        card.hb.hovered = true;
        card.hb.justHovered = true;
        card.hb.clickStarted = true;
        card.hb.clicked = true;
        AbstractPlayer player = AbstractDungeon.player;
        if (player != null) {
            player.hoveredCard = card;
            player.toHover = card;
            setPrivateField(player, AbstractPlayer.class, "isHoveringCard", Boolean.TRUE);
        }
        queueLeftPress();
    }
    // Step 2: move into the drop zone and release so the base game decides whether target mode should open.
    private static void queueDropZoneRelease(AbstractPlayer player) {
        int dropX = Settings.WIDTH / 2;
        int dropY = computeDropZoneY(player);
        moveCursor(dropX, dropY);
        player.isHoveringDropZone = true;
        queueLeftRelease();
    }
    // Step 3: for targeted cards, move to the monster and release again.
    private static void queueTargetRelease(AbstractMonster target) {
        moveCursorToHitbox(target.hb);
        target.hb.hovered = true;
        target.hb.justHovered = true;
        setPrivateField(AbstractDungeon.player, AbstractPlayer.class, "hoveredMonster", target);
        queueLeftRelease();
    }

    private static void queuePlayerTargetRelease() {
        if (AbstractDungeon.player == null || AbstractDungeon.player.hb == null) {
            throw new IllegalStateException("Player target hitbox is unavailable");
        }
        moveCursorToHitbox(AbstractDungeon.player.hb);
        AbstractDungeon.player.hb.hovered = true;
        AbstractDungeon.player.hb.justHovered = true;
        setPrivateField(AbstractDungeon.player, AbstractPlayer.class, "hoveredMonster", null);
        queueLeftRelease();
    }

    private static void queueEventPress(Hitbox hitbox) {
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = false;
        queueLeftPress();
    }

    private static void queueEventRelease(Hitbox hitbox) {
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = true;
        queueLeftRelease();
    }

    private static void queueShopPress(Hitbox hitbox) {
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        // ?????? hitbox ???/??????????????
        hitbox.clickStarted = true;
        hitbox.clicked = false;
        queueLeftPress();
    }

    private static void queueShopRelease(Hitbox hitbox) {
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = true;
        queueLeftRelease();
    }

    private static void queueShopPress(int targetX, int targetY) {
        moveCursor(targetX, targetY);
        queueLeftPress();
    }

    private static void queueShopRelease(int targetX, int targetY) {
        moveCursor(targetX, targetY);
        queueLeftRelease();
    }

    private static void queuePotionPress(AbstractPotion potion) {
        moveCursorToHitbox(potion.hb);
        potion.hb.hovered = true;
        potion.hb.justHovered = true;
        potion.hb.clickStarted = true;
        potion.hb.clicked = false;
        queueLeftPress();
    }

    private static void queuePotionRelease(AbstractPotion potion) {
        moveCursorToHitbox(potion.hb);
        potion.hb.hovered = true;
        potion.hb.justHovered = true;
        potion.hb.clickStarted = true;
        potion.hb.clicked = true;
        queueLeftRelease();
    }

    private static void queuePotionPopupPress(PotionPopUp popup) {
        Hitbox hitbox = getPotionPopupTopHitbox(popup);
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = false;
        queueLeftPress();
    }

    private static void queuePotionPopupRelease(PotionPopUp popup) {
        Hitbox hitbox = getPotionPopupTopHitbox(popup);
        moveCursorToHitbox(hitbox);
        hitbox.hovered = true;
        hitbox.justHovered = true;
        hitbox.clickStarted = true;
        hitbox.clicked = true;
        queueLeftRelease();
    }

    private static void moveCursorToHitbox(Hitbox hitbox) {
        if (hitbox == null) {
            throw new IllegalStateException("Target hitbox is missing");
        }
        moveCursor(Math.round(hitbox.cX), Math.round(hitbox.cY));
    }

    private static void moveCursor(int targetX, int targetY) {
        InputHelper.mX = targetX;
        InputHelper.mY = targetY;
        Gdx.input.setCursorPosition(targetX, Settings.HEIGHT - targetY);
    }

    private static void queueMapClick(DungeonMapScreen mapScreen, Hitbox hitbox) {
        mapScreen.clicked = true;
        mapScreen.clickTimer = 0.0F;
        setPrivateField(mapScreen, DungeonMapScreen.class, "clickStartX", Float.valueOf(hitbox.cX));
        setPrivateField(mapScreen, DungeonMapScreen.class, "clickStartY", Float.valueOf(hitbox.cY));
        hitbox.clickStarted = true;
        InputHelper.justReleasedClickLeft = true;
        InputHelper.justClickedLeft = true;
        InputHelper.isMouseDown = false;
    }

    private static void setPrivateField(Object target, Class<?> owner, String fieldName, Object value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to set field: " + fieldName, ex);
        }
    }

    private static Object getPrivateField(Object target, Class<?> owner, String fieldName) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read field: " + fieldName, ex);
        }
    }

    private static ShopScreen requireShopScreen() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        boolean inShopRoom = room != null && room.getClass().getSimpleName().contains("ShopRoom");
        // ????????????current_screen ????? NONE????? CurrentScreen.SHOP ???
        if (AbstractDungeon.shopScreen == null || (!inShopRoom && AbstractDungeon.screen != AbstractDungeon.CurrentScreen.SHOP)) {
            throw new IllegalStateException("Shop screen is not active");
        }
        return AbstractDungeon.shopScreen;
    }

    private static AbstractCard resolveShopCard(ShopScreen shopScreen, String cardUuid, Integer cardIndex) {
        ArrayList<AbstractCard> cards = new ArrayList<AbstractCard>();
        if (shopScreen.coloredCards != null) {
            cards.addAll(shopScreen.coloredCards);
        }
        if (shopScreen.colorlessCards != null) {
            cards.addAll(shopScreen.colorlessCards);
        }
        CardGroup group = new CardGroup(CardGroup.CardGroupType.UNSPECIFIED);
        group.group.addAll(cards);
        return resolveCardFromGroup(group, cardUuid, cardIndex, "shop_cards");
    }

    @SuppressWarnings("unchecked")
    private static StoreRelic resolveShopRelic(int optionIndex) {
        ShopScreen shopScreen = requireShopScreen();
        ArrayList<StoreRelic> relics = (ArrayList<StoreRelic>) ReflectionHacks.getPrivate(shopScreen, ShopScreen.class, "relics");
        if (relics == null || optionIndex < 0 || optionIndex >= relics.size()) {
            throw new IllegalArgumentException("option_index out of range: " + optionIndex);
        }
        return relics.get(optionIndex);
    }

    @SuppressWarnings("unchecked")
    private static StorePotion resolveShopPotion(int optionIndex) {
        ShopScreen shopScreen = requireShopScreen();
        ArrayList<StorePotion> potions = (ArrayList<StorePotion>) ReflectionHacks.getPrivate(shopScreen, ShopScreen.class, "potions");
        if (potions == null || optionIndex < 0 || optionIndex >= potions.size()) {
            throw new IllegalArgumentException("option_index out of range: " + optionIndex);
        }
        return potions.get(optionIndex);
    }

    private static AbstractPotion resolveCombatPotion(int optionIndex) {
        if (AbstractDungeon.player == null || AbstractDungeon.player.potions == null) {
            throw new IllegalStateException("Potion slots are unavailable");
        }
        for (AbstractPotion potion : AbstractDungeon.player.potions) {
            if (potion != null && potion.slot == optionIndex) {
                return potion;
            }
        }
        if (optionIndex >= 0 && optionIndex < AbstractDungeon.player.potions.size()) {
            return AbstractDungeon.player.potions.get(optionIndex);
        }
        throw new IllegalArgumentException("option_index out of range: " + optionIndex);
    }

    private static AbstractPotion resolvePendingPotion(int slot) {
        if (AbstractDungeon.player == null || AbstractDungeon.player.potions == null) {
            return null;
        }
        for (AbstractPotion potion : AbstractDungeon.player.potions) {
            if (potion != null && potion.slot == slot) {
                return potion;
            }
        }
        return null;
    }

    private static boolean isPotionTargetingActive() {
        return AbstractDungeon.topPanel != null
                && AbstractDungeon.topPanel.potionUi != null
                && AbstractDungeon.topPanel.potionUi.targetMode;
    }

    private static PotionPopUp getPotionPopup() {
        if (AbstractDungeon.topPanel == null) {
            return null;
        }
        return AbstractDungeon.topPanel.potionUi;
    }

    private static boolean isPotionPopupVisible(PotionPopUp popup) {
        return popup != null && !popup.isHidden;
    }

    private static Hitbox getPotionPopupTopHitbox(PotionPopUp popup) {
        if (popup == null) {
            throw new IllegalStateException("Potion popup is unavailable");
        }
        Object raw = getPrivateField(popup, PotionPopUp.class, "hbTop");
        if (!(raw instanceof Hitbox)) {
            throw new IllegalStateException("Potion popup confirm hitbox is unavailable");
        }
        return (Hitbox) raw;
    }

    private static LargeDialogOptionButton resolveActiveEventButton(int optionIndex) {
        try {
            LargeDialogOptionButton button = resolveEventButton(optionIndex);
            if (button != null && !button.isDisabled) {
                return button;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static LargeDialogOptionButton resolveEventButton(int optionIndex) {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.event == null) {
            throw new IllegalStateException("No active event is available");
        }
        ArrayList<LargeDialogOptionButton> buttons = new ArrayList<LargeDialogOptionButton>();
        if (room.event.imageEventText != null && room.event.imageEventText.optionList != null && !room.event.imageEventText.optionList.isEmpty()) {
            buttons.addAll(room.event.imageEventText.optionList);
        } else if (RoomEventDialog.optionList != null && !RoomEventDialog.optionList.isEmpty()) {
            buttons.addAll(RoomEventDialog.optionList);
        }
        for (LargeDialogOptionButton button : buttons) {
            if (button != null && button.slot == optionIndex) {
                return button;
            }
        }
        throw new IllegalArgumentException("Event option not found: " + optionIndex);
    }

    private static AbstractCard resolveCardFromGroup(CardGroup group, String cardUuid, Integer cardIndex, String source) {
        if (group == null || group.group == null) {
            throw new IllegalStateException("Card group is unavailable: " + source);
        }
        if (cardUuid != null && !cardUuid.trim().isEmpty()) {
            for (AbstractCard card : group.group) {
                if (cardUuid.equals(card.uuid.toString())) {
                    return card;
                }
            }
            throw new IllegalArgumentException("Card uuid not found: " + cardUuid);
        }
        int index = requireInt(cardIndex, "card_index");
        if (index < 0 || index >= group.group.size()) {
            throw new IllegalArgumentException("card_index out of range: " + index);
        }
        return group.group.get(index);
    }

    private static AbstractMonster resolveTarget(AbstractCard card, Integer targetIndex) {
        boolean needsTarget = requiresTarget(card);
        if (!needsTarget) {
            return null;
        }
        if (AbstractDungeon.getCurrRoom() == null || AbstractDungeon.getCurrRoom().monsters == null) {
            throw new IllegalStateException("No monsters are available");
        }
        int index = requireInt(targetIndex, "target_index");
        ArrayList<AbstractMonster> monsters = AbstractDungeon.getCurrRoom().monsters.monsters;
        if (index < 0 || index >= monsters.size()) {
            throw new IllegalArgumentException("target_index out of range: " + index);
        }
        AbstractMonster monster = monsters.get(index);
        if (monster.isDeadOrEscaped()) {
            throw new IllegalStateException("Target monster is already dead or escaped");
        }
        return monster;
    }

    private static AbstractMonster resolveMonsterTarget(int targetIndex) {
        if (AbstractDungeon.getCurrRoom() == null || AbstractDungeon.getCurrRoom().monsters == null) {
            throw new IllegalStateException("No monsters are available");
        }
        ArrayList<AbstractMonster> monsters = AbstractDungeon.getCurrRoom().monsters.monsters;
        if (targetIndex < 0 || targetIndex >= monsters.size()) {
            throw new IllegalArgumentException("target_index out of range: " + targetIndex);
        }
        AbstractMonster monster = monsters.get(targetIndex);
        if (monster.isDeadOrEscaped()) {
            throw new IllegalStateException("Target monster is already dead or escaped");
        }
        return monster;
    }

    private static AbstractMonster resolvePendingTarget(int targetIndex) {
        if (AbstractDungeon.getCurrRoom() == null || AbstractDungeon.getCurrRoom().monsters == null) {
            return null;
        }
        ArrayList<AbstractMonster> monsters = AbstractDungeon.getCurrRoom().monsters.monsters;
        if (targetIndex < 0 || targetIndex >= monsters.size()) {
            return null;
        }
        AbstractMonster monster = monsters.get(targetIndex);
        return monster.isDeadOrEscaped() ? null : monster;
    }

    private static CampfireUI requireCampfireUi() {
        CampfireUI campfireUI = tryCampfireUi();
        if (campfireUI == null) {
            throw new IllegalStateException("Campfire UI is not available");
        }
        return campfireUI;
    }

    private static CampfireUI tryCampfireUi() {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null) {
            return null;
        }
        Object campfireUiObj = ReflectionHacks.getPrivate(room, room.getClass(), "campfireUI");
        return campfireUiObj instanceof CampfireUI ? (CampfireUI) campfireUiObj : null;
    }

    private static boolean isMapNodeSelectable(MapRoomNode node) {
        if (node == null || node.room == null || node.taken) {
            return false;
        }
        if (!AbstractDungeon.firstRoomChosen) {
            return node.y == 0;
        }
        MapRoomNode current = AbstractDungeon.getCurrMapNode();
        return current != null && (current.isConnectedTo(node) || current.wingedIsConnectedTo(node));
    }

    private static MapRoomNode findMapNode(int x, int y) {
        if (AbstractDungeon.map == null) {
            return null;
        }
        for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
            for (MapRoomNode node : row) {
                if (node != null && node.x == x && node.y == y) {
                    return node;
                }
            }
        }
        return null;
    }

    private static int requireInt(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.intValue();
    }

    private static boolean requiresTarget(AbstractCard card) {
        return card != null && (card.target == AbstractCard.CardTarget.ENEMY || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY);
    }

    private static AbstractCard findCardInHand(String cardUuid) {
        CardGroup hand = AbstractDungeon.player == null ? null : AbstractDungeon.player.hand;
        if (hand == null || hand.group == null) {
            return null;
        }
        for (AbstractCard card : hand.group) {
            if (cardUuid.equals(card.uuid.toString())) {
                return card;
            }
        }
        return null;
    }

    private static boolean isDraggingPendingCard(AbstractPlayer player, String cardUuid) {
        return player != null
                && player.isDraggingCard
                && player.hoveredCard != null
                && cardUuid.equals(player.hoveredCard.uuid.toString());
    }
    // Drop Y has to stay inside the game's accepted drag band, otherwise the card stays selected but is not played.
    private static int computeDropZoneY(AbstractPlayer player) {
        float hoverStartLine = 0.0F;
        Object rawHoverStartLine = getPrivateField(player, AbstractPlayer.class, "hoverStartLine");
        if (rawHoverStartLine instanceof Float) {
            hoverStartLine = ((Float) rawHoverStartLine).floatValue();
        }
        float minDropY = Math.max(350.0F * Settings.scale, hoverStartLine + 12.0F * Settings.scale);
        float maxDropY = Settings.CARD_DROP_END_Y - 12.0F * Settings.scale;
        if (maxDropY <= 0.0F) {
            maxDropY = Settings.HEIGHT * 0.75F;
        }
        float clampedY = Math.min(Math.max(minDropY, 0.0F), Math.max(maxDropY, 0.0F));
        if (clampedY <= 0.0F) {
            clampedY = Settings.HEIGHT * 0.5F;
        }
        return Math.round(clampedY);
    }

    private enum PlayCardPhase {
        SELECT_CARD,
        WAIT_FOR_DRAG,
        WAIT_FOR_TARGET_MODE,
        WAIT_FOR_RESOLUTION
    }

    private enum PotionUsePhase {
        PRESS_POTION_SLOT,
        RELEASE_POTION_SLOT,
        WAIT_FOR_POPUP,
        RELEASE_POPUP_CONFIRM,
        WAIT_FOR_TARGET_MODE,
        WAIT_FOR_RESOLUTION
    }

    private enum ShopClickPhase {
        PRESS,
        RELEASE
    }

    private static final class PendingPlayCard {
        private final String cardUuid;
        private final int targetIndex;
        private final boolean requiresTarget;
        private PlayCardPhase phase;
        private int remainingTicks;

        private PendingPlayCard(String cardUuid, int targetIndex, boolean requiresTarget) {
            this.cardUuid = cardUuid;
            this.targetIndex = targetIndex;
            this.requiresTarget = requiresTarget;
            this.phase = PlayCardPhase.SELECT_CARD;
            this.remainingTicks = PENDING_INPUT_TIMEOUT_TICKS;
        }

        private static PendingPlayCard create(AbstractCard card, AbstractMonster target, boolean requiresTarget) {
            int targetIndex = -1;
            if (target != null && AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().monsters != null) {
                targetIndex = AbstractDungeon.getCurrRoom().monsters.monsters.indexOf(target);
            }
            return new PendingPlayCard(card.uuid.toString(), targetIndex, requiresTarget);
        }
    }

    private static final class PendingPotionUse {
        private final int slot;
        private final int targetIndex;
        private final boolean requiresTarget;
        private final boolean targetSelf;
        private PotionUsePhase phase;
        private int remainingTicks;

        private PendingPotionUse(int slot, int targetIndex, boolean requiresTarget, boolean targetSelf) {
            this.slot = slot;
            this.targetIndex = targetIndex;
            this.requiresTarget = requiresTarget;
            this.targetSelf = targetSelf;
            this.phase = PotionUsePhase.PRESS_POTION_SLOT;
            this.remainingTicks = Math.max(24, PENDING_INPUT_TIMEOUT_TICKS / 3);
        }

        private static PendingPotionUse create(AbstractPotion potion, AbstractMonster target, boolean requiresTarget, boolean targetSelf) {
            int targetIndex = -1;
            if (target != null && AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().monsters != null) {
                targetIndex = AbstractDungeon.getCurrRoom().monsters.monsters.indexOf(target);
            }
            return new PendingPotionUse(potion.slot, targetIndex, requiresTarget, targetSelf);
        }
    }

    private enum EventClickPhase {
        PRESS,
        RELEASE
    }

    private static final class PendingEventClick {
        private final int optionIndex;
        private final Hitbox hitbox;
        private EventClickPhase phase;
        private int remainingTicks;

        private PendingEventClick(int optionIndex, Hitbox hitbox) {
            this.optionIndex = optionIndex;
            this.hitbox = hitbox;
            this.phase = EventClickPhase.PRESS;
            this.remainingTicks = Math.max(12, PENDING_INPUT_TIMEOUT_TICKS / 8);
        }

        private static PendingEventClick create(int optionIndex, Hitbox hitbox) {
            if (hitbox == null) {
                throw new IllegalStateException("Event option hitbox is missing");
            }
            return new PendingEventClick(optionIndex, hitbox);
        }
    }

    private static final class PendingShopClick {
        private final Hitbox hitbox;
        private final int x;
        private final int y;
        private ShopClickPhase phase;
        private int remainingTicks;

        private PendingShopClick(Hitbox hitbox, int x, int y) {
            this.hitbox = hitbox;
            this.x = x;
            this.y = y;
            this.phase = ShopClickPhase.PRESS;
            this.remainingTicks = Math.max(12, PENDING_INPUT_TIMEOUT_TICKS / 8);
        }

        private static PendingShopClick forHitbox(Hitbox hitbox) {
            if (hitbox == null) {
                throw new IllegalStateException("Shop target hitbox is missing");
            }
            return new PendingShopClick(hitbox, 0, 0);
        }

        private static PendingShopClick forPoint(int x, int y) {
            return new PendingShopClick(null, x, y);
        }
    }
}






