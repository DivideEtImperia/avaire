/*
 * Copyright (c) 2018.
 *
 * This file is part of AvaIre.
 *
 * AvaIre is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AvaIre is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AvaIre.  If not, see <https://www.gnu.org/licenses/>.
 *
 *
 */

package com.avairebot.database.controllers;

import com.avairebot.AvaIre;
import com.avairebot.Constants;
import com.avairebot.database.transformers.PlayerTransformer;
import com.avairebot.level.ExperienceEntity;
import com.avairebot.utilities.CacheUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class PlayerController {

    public static final Cache<Object, Object> cache = CacheBuilder.newBuilder()
        .recordStats()
        .expireAfterAccess(210, TimeUnit.SECONDS) // 3½ minute
        .build();

    private static final Map<Long, PlayerUpdateReference> playerQueue = new LinkedHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(PlayerController.class);

    private static final String[] requiredPlayerColumns = new String[]{
        "username", "discriminator", "avatar", "experience"
    };

    @CheckReturnValue
    public static PlayerTransformer fetchPlayer(AvaIre avaire, Message message) {
        return fetchPlayer(avaire, message, message.getAuthor());
    }

    @CheckReturnValue
    public static PlayerTransformer fetchPlayer(AvaIre avaire, Message message, User user) {
        if (!message.getChannelType().isGuild()) {
            return null;
        }

        return (PlayerTransformer) CacheUtil.getUncheckedUnwrapped(cache, asKey(message.getGuild(), user), () -> {
            log.debug("User cache for " + user.getId() + " was refreshed");

            try {
                PlayerTransformer transformer = new PlayerTransformer(
                    user.getIdLong(),
                    message.getGuild().getIdLong(),
                    avaire.getDatabase()
                        .newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                        .select(requiredPlayerColumns)
                        .where("user_id", user.getId())
                        .andWhere("guild_id", message.getGuild().getId())
                        .get().first()
                );

                if (!transformer.hasData()) {
                    transformer.incrementExperienceBy(100);
                    transformer.setUsername(user.getName());
                    transformer.setDiscriminator(user.getDiscriminator());
                    transformer.setAvatar(user.getAvatarId());

                    avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                        .insert(statement -> {
                            statement.set("guild_id", message.getGuild().getId())
                                .set("user_id", user.getId())
                                .set("username", user.getName(), true)
                                .set("discriminator", user.getDiscriminator())
                                .set("avatar", user.getAvatarId())
                                .set("experience", 100);
                        });

                    return mergeWithExperienceEntity(avaire, transformer);
                }

                if (isChanged(user, transformer)) {
                    transformer.setUsername(user.getName());
                    transformer.setDiscriminator(user.getDiscriminator());
                    transformer.setAvatar(user.getAvatarId());

                    updateUserData(user);

                    return mergeWithExperienceEntity(avaire, transformer);
                }

                // If the users name haven't been encoded yet, we'll do it below.
                String username = transformer.getUsernameRaw();
                if (username.startsWith("base64:")) {
                    return mergeWithExperienceEntity(avaire, transformer);
                }

                avaire.getDatabase().newQueryBuilder(Constants.PLAYER_EXPERIENCE_TABLE_NAME)
                    .useAsync(true)
                    .where("user_id", message.getAuthor().getId())
                    .update(statement -> statement.set("username", message.getAuthor().getName(), true));

                return mergeWithExperienceEntity(avaire, transformer);
            } catch (Exception ex) {
                AvaIre.getLogger().error(ex.getMessage(), ex);
                return null;
            }
        });
    }

    private static PlayerTransformer mergeWithExperienceEntity(AvaIre avaire, PlayerTransformer transformer) {
        List<ExperienceEntity> entities = avaire.getLevelManager().getExperienceEntities(transformer);
        if (entities.isEmpty()) {
            return transformer;
        }

        transformer.incrementExperienceBy(
            entities.stream().mapToInt(ExperienceEntity::getExperience).sum()
        );

        return transformer;
    }

    public static Map<Long, PlayerUpdateReference> getPlayerQueue() {
        return playerQueue;
    }

    public static void updateUserData(User user) {
        playerQueue.put(user.getIdLong(), new PlayerUpdateReference(user));
    }

    private static boolean isChanged(User user, PlayerTransformer transformer) {
        return !user.getName().equals(transformer.getUsername())
            || !user.getDiscriminator().equals(transformer.getDiscriminator())
            || !Objects.equals(user.getAvatarId(), transformer.getAvatar());
    }

    private static String asKey(@Nonnull Guild guild, @Nonnull User user) {
        return guild.getId() + ":" + user.getId();
    }

    public static class PlayerUpdateReference {

        private final String username;
        private final String discriminator;
        private final String avatar;

        PlayerUpdateReference(@Nonnull User user) {
            this.username = user.getName();
            this.discriminator = user.getDiscriminator();
            this.avatar = user.getAvatarId();
        }

        public String getUsername() {
            return username;
        }

        public String getDiscriminator() {
            return discriminator;
        }

        public String getAvatar() {
            return avatar;
        }
    }
}
