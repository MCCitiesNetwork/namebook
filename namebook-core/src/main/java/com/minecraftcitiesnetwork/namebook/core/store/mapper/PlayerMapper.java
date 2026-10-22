package com.minecraftcitiesnetwork.namebook.core.store.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PlayerMapper {

    /** One stored player. */
    record Row(UUID id, String name) {
    }

    @Delete("DELETE FROM namebook_players WHERE name = #{name} AND player_uuid <> #{id}")
    int deleteOtherHolders(@Param("id") UUID id, @Param("name") String name);

    @Insert("""
            INSERT INTO namebook_players (player_uuid, name) VALUES (#{id}, #{name})
            ON DUPLICATE KEY UPDATE name = VALUES(name)""")
    int upsert(@Param("id") UUID id, @Param("name") String name);

    // Not INSERT IGNORE: that would also turn a too-long name into a silently cut one. A row that
    // appears between the check and the insert raises a duplicate key, which PlayerStore handles.
    @Insert("""
            INSERT INTO namebook_players (player_uuid, name)
            SELECT #{id}, #{name} FROM DUAL WHERE NOT EXISTS
              (SELECT 1 FROM namebook_players WHERE player_uuid = #{id} OR name = #{name})""")
    int insertIfAbsent(@Param("id") UUID id, @Param("name") String name);

    @Select("SELECT name FROM namebook_players WHERE player_uuid = #{id}")
    @Nullable String nameOf(@Param("id") UUID id);

    // Plain "=" on a case-insensitive collation: "_" is an ordinary character here, unlike in LIKE.
    @Select("SELECT player_uuid FROM namebook_players WHERE name = #{name}")
    @Nullable UUID uuidOf(@Param("name") String name);

    @Select("""
            <script>
            SELECT player_uuid, name FROM namebook_players WHERE player_uuid IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>""")
    @ConstructorArgs({@Arg(column = "player_uuid", javaType = UUID.class), @Arg(column = "name", javaType = String.class)})
    List<Row> namesOf(@Param("ids") Collection<UUID> ids);

    @Select("""
            <script>
            SELECT player_uuid, name FROM namebook_players WHERE name IN
            <foreach collection="names" item="n" open="(" separator="," close=")">#{n}</foreach>
            </script>""")
    @ConstructorArgs({@Arg(column = "player_uuid", javaType = UUID.class), @Arg(column = "name", javaType = String.class)})
    List<Row> uuidsOf(@Param("names") Collection<String> names);
}
