package dev.infectedware.tellmeyourmods;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private Text() {
    }

    public static Component color(String input) {
        return input == null || input.isEmpty() ? Component.empty() : LEGACY.deserialize(input);
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static Component replace(Component component, String placeholder, Component replacement) {
        return component.replaceText(TextReplacementConfig.builder()
                .matchLiteral(placeholder)
                .replacement(replacement)
                .build());
    }
}
