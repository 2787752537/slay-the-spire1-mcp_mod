package stsmodstarter.bridge;

import basemod.ReflectionHacks;
import com.megacrit.cardcrawl.actions.utility.NewQueueCardAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardGroup;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.events.AbstractEvent;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.CampfireUI;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.DungeonMapScreen;
import com.megacrit.cardcrawl.screens.select.HandCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.CardSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.ConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.GridSelectConfirmButton;
import com.megacrit.cardcrawl.ui.buttons.ProceedButton;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;

import java.lang.reflect.Method;
import java.util.ArrayList;

public final class BridgeActions {
    private BridgeActions() {
    }

    public static String playCard(BridgeProtocol.CommandEnvelope command) {
        requireContext("combat");
        AbstractCard card = resolveCardFromGroup(AbstractDungeon.player.hand, command.card_uuid, command.card_index, "hand");
        AbstractMonster target = resolveTarget(card, command.target_index);
        if (!card.canUse(AbstractDungeon.player, target)) {
            throw new IllegalStateException("Card cannot be used right now: " + card.name);
        }
        if (card.cost == -1) {
            card.energyOnUse = EnergyPanel.totalCount;
        }
        AbstractDungeon.actionManager.addToBottom(new NewQueueCardAction(card, target, false, true));
        return "Queued card: " + card.name;
    }

    public static String endTurn() {
        requireContext("combat");
        AbstractDungeon.actionManager.endTurn();
        return "End turn requested";
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
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.rewards == null) {
            throw new IllegalStateException("No room rewards are available");
        }
        int rewardIndex = requireInt(command.reward_index, "reward_index");
        if (rewardIndex < 0 || rewardIndex >= room.rewards.size()) {
            throw new IllegalArgumentException("reward_index out of range: " + rewardIndex);
        }
        RewardItem reward = room.rewards.get(rewardIndex);
        reward.claimReward();
        return "Claimed room reward " + rewardIndex;
    }

    public static String chooseCardReward(BridgeProtocol.CommandEnvelope command) throws Exception {
        if (AbstractDungeon.cardRewardScreen == null || AbstractDungeon.cardRewardScreen.rewardGroup == null) {
            throw new IllegalStateException("Card reward screen is not open");
        }
        int rewardIndex = requireInt(command.reward_index, "reward_index");
        if (rewardIndex < 0 || rewardIndex >= AbstractDungeon.cardRewardScreen.rewardGroup.size()) {
            throw new IllegalArgumentException("reward_index out of range: " + rewardIndex);
        }
        AbstractCard card = AbstractDungeon.cardRewardScreen.rewardGroup.get(rewardIndex);
        Method acquire = CardRewardScreen.class.getDeclaredMethod("acquireCard", AbstractCard.class);
        acquire.setAccessible(true);
        acquire.invoke(AbstractDungeon.cardRewardScreen, card);
        Method takeReward = CardRewardScreen.class.getDeclaredMethod("takeReward");
        takeReward.setAccessible(true);
        takeReward.invoke(AbstractDungeon.cardRewardScreen);
        return "Selected card reward: " + card.name;
    }

    public static String skipCardReward() {
        if (AbstractDungeon.cardRewardScreen == null) {
            throw new IllegalStateException("Card reward screen is not open");
        }
        AbstractDungeon.cardRewardScreen.skippedCards();
        return "Skipped card reward";
    }

    public static String chooseEventOption(BridgeProtocol.CommandEnvelope command) throws Exception {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        if (room == null || room.event == null) {
            throw new IllegalStateException("No active event is available");
        }
        int optionIndex = requireInt(command.option_index, "option_index");
        Method method = findMethod(room.event.getClass(), "buttonEffect", int.class);
        if (method == null) {
            throw new NoSuchMethodException("buttonEffect(int)");
        }
        method.setAccessible(true);
        method.invoke(room.event, optionIndex);
        return "Selected event option " + optionIndex;
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
        option.useOption();
        return "Selected campfire option " + optionIndex;
    }

    public static String chooseMapNode(BridgeProtocol.CommandEnvelope command) {
        int x = requireInt(command.x, "x");
        int y = requireInt(command.y, "y");
        MapRoomNode node = findMapNode(x, y);
        if (node == null) {
            throw new IllegalArgumentException("Map node not found: (" + x + ", " + y + ")");
        }
        DungeonMapScreen mapScreen = AbstractDungeon.dungeonMapScreen;
        mapScreen.clicked = true;
        mapScreen.clickTimer = 0.0F;
        click(node.hb);
        return "Clicked map node (" + x + ", " + y + ")";
    }

    public static String selectHandCard(BridgeProtocol.CommandEnvelope command) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.HAND_SELECT) {
            throw new IllegalStateException("Hand selection screen is not active");
        }
        CardGroup hand = (CardGroup) ReflectionHacks.getPrivate(AbstractDungeon.handCardSelectScreen, HandCardSelectScreen.class, "hand");
        AbstractCard card = resolveCardFromGroup(hand, command.card_uuid, command.card_index, "hand_select");
        click(card.hb);
        return "Selected hand card: " + card.name;
    }

    public static String selectGridCard(BridgeProtocol.CommandEnvelope command) {
        if (AbstractDungeon.screen != AbstractDungeon.CurrentScreen.GRID || AbstractDungeon.gridSelectScreen.targetGroup == null) {
            throw new IllegalStateException("Grid selection screen is not active");
        }
        AbstractCard card = resolveCardFromGroup(AbstractDungeon.gridSelectScreen.targetGroup, command.card_uuid, command.card_index, "grid_select");
        click(card.hb);
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

    private static void requireContext(String expected) {
        AbstractRoom room = AbstractDungeon.getCurrRoom();
        String actual = room != null && room.phase == AbstractRoom.RoomPhase.COMBAT ? "combat" : "room";
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Expected context '" + expected + "' but was '" + actual + "'");
        }
    }

    private static void click(Hitbox hitbox) {
        if (hitbox == null) {
            throw new IllegalStateException("Target hitbox is missing");
        }
        hitbox.clickStarted = true;
        hitbox.clicked = true;
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
        boolean needsTarget = card != null && (card.target == AbstractCard.CardTarget.ENEMY || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY);
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

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
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
}