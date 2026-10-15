package com.febridge.user;

import com.febridge.user.UserDtos.UserRecord;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    UserRecord byUsername(@Param("username") String username);
    UserRecord lockByUsername(@Param("username") String username);
    UserRecord byId(@Param("id") UUID id);
    List<UserRecord> list();
    int count();
    int claimLegacySessions();
    int claimLegacyEvents();
    int enabledAdmins();
    void lockChanges();
    int insert(
        @Param("id") UUID id,
        @Param("username") String username,
        @Param("displayName") String displayName,
        @Param("passwordHash") String passwordHash,
        @Param("role") String role
    );
    int update(
        @Param("id") UUID id,
        @Param("displayName") String displayName,
        @Param("role") String role,
        @Param("enabled") boolean enabled
    );
    int password(@Param("id") UUID id, @Param("passwordHash") String passwordHash);
    int failedLogin(@Param("id") UUID id);
    int successfulLogin(@Param("id") UUID id);
}
