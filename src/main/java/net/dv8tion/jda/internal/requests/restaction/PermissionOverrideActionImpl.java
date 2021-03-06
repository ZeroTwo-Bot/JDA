/*
 * Copyright 2015-2020 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.internal.requests.restaction;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.MissingAccessException;
import net.dv8tion.jda.api.requests.Request;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.restaction.PermissionOverrideAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.entities.AbstractChannelImpl;
import net.dv8tion.jda.internal.entities.PermissionOverrideImpl;
import net.dv8tion.jda.internal.requests.Route;
import net.dv8tion.jda.internal.utils.Checks;
import okhttp3.RequestBody;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class PermissionOverrideActionImpl
    extends AuditableRestActionImpl<PermissionOverride>
    implements PermissionOverrideAction
{
    private boolean isOverride = true;
    private boolean allowSet = false;
    private boolean denySet = false;

    private long allow = 0;
    private long deny = 0;
    private final AbstractChannelImpl<?, ?> channel;
    private final IPermissionHolder permissionHolder;
    private final boolean isRole;
    private final long id;

    public PermissionOverrideActionImpl(PermissionOverride override)
    {
        super(override.getJDA(), Route.Channels.MODIFY_PERM_OVERRIDE.compile(override.getChannel().getId(), override.getId()));
        this.channel = (AbstractChannelImpl<?, ?>) override.getChannel();
        this.permissionHolder = override.getPermissionHolder();
        this.isRole = override.isRoleOverride();
        this.id = override.getIdLong();
    }

    public PermissionOverrideActionImpl(JDA api, GuildChannel channel, IPermissionHolder permissionHolder)
    {
        super(api, Route.Channels.CREATE_PERM_OVERRIDE.compile(channel.getId(), permissionHolder.getId()));
        this.channel = (AbstractChannelImpl<?, ?>) channel;
        this.permissionHolder = permissionHolder;
        this.isRole = permissionHolder instanceof Role;
        this.id = permissionHolder.getIdLong();
    }

    // Whether to keep original value of the current override or not - by default we override the value
    public PermissionOverrideActionImpl setOverride(boolean override)
    {
        isOverride = override;
        return this;
    }

    @Override
    protected BooleanSupplier finalizeChecks()
    {
        return () -> {

            Member selfMember = getGuild().getSelfMember();
            if (!selfMember.hasPermission(channel, Permission.VIEW_CHANNEL))
                throw new MissingAccessException(channel, Permission.VIEW_CHANNEL);
            if (!selfMember.hasAccess(channel))
                throw new MissingAccessException(channel, Permission.VOICE_CONNECT);
            if (!selfMember.hasPermission(channel, Permission.MANAGE_PERMISSIONS))
                throw new InsufficientPermissionException(channel, Permission.MANAGE_PERMISSIONS);
            return true;
        };
    }

    @Nonnull
    @Override
    public PermissionOverrideActionImpl setCheck(BooleanSupplier checks)
    {
        return (PermissionOverrideActionImpl) super.setCheck(checks);
    }

    @Nonnull
    @Override
    public PermissionOverrideActionImpl timeout(long timeout, @Nonnull TimeUnit unit)
    {
        return (PermissionOverrideActionImpl) super.timeout(timeout, unit);
    }

    @Nonnull
    @Override
    public PermissionOverrideActionImpl deadline(long timestamp)
    {
        return (PermissionOverrideActionImpl) super.deadline(timestamp);
    }

    @Nonnull
    @Override
    public PermissionOverrideAction resetAllow()
    {
        allow = getCurrentAllow();
        allowSet = false;
        return this;
    }

    @Nonnull
    @Override
    public PermissionOverrideAction resetDeny()
    {
        deny = getCurrentDeny();
        denySet = false;
        return this;
    }

    @Nonnull
    @Override
    public GuildChannel getChannel()
    {
        return channel;
    }

    @Override
    public Role getRole()
    {
        return isRole() ? (Role) permissionHolder : null;
    }

    @Override
    public Member getMember()
    {
        return isMember() ? (Member) permissionHolder : null;
    }

    @Override
    public long getAllow()
    {
        return allow;
    }

    @Override
    public long getDeny()
    {
        return deny;
    }

    @Override
    public long getInherited()
    {
        return ~allow & ~deny;
    }

    @Override
    public boolean isMember()
    {
        return !isRole;
    }

    @Override
    public boolean isRole()
    {
        return isRole;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public PermissionOverrideActionImpl setAllow(long allowBits)
    {
        Checks.notNegative(allowBits, "Granted permissions value");
        Checks.check(allowBits <= Permission.ALL_PERMISSIONS, "Specified allow value may not be greater than a full permission set");
        this.allow = allowBits;
        this.deny &= ~allowBits;
        allowSet = denySet = true;
        return this;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public PermissionOverrideActionImpl setDeny(long denyBits)
    {
        Checks.notNegative(denyBits, "Denied permissions value");
        Checks.check(denyBits <= Permission.ALL_PERMISSIONS, "Specified deny value may not be greater than a full permission set");
        this.deny = denyBits;
        this.allow &= ~denyBits;
        allowSet = denySet = true;
        return this;
    }

    @Nonnull
    @Override
    @CheckReturnValue
    public PermissionOverrideActionImpl setPermissions(long allowBits, long denyBits)
    {
        return setAllow(allowBits).setDeny(denyBits);
    }

    private long getCurrentAllow()
    {
        if (isOverride)
            return 0;
        PermissionOverride override = channel.getOverrideMap().get(id);
        return override == null ? 0 : override.getAllowedRaw();
    }

    private long getCurrentDeny()
    {
        if (isOverride)
            return 0;
        PermissionOverride override = channel.getOverrideMap().get(id);
        return override == null ? 0 : override.getDeniedRaw();
    }

    @Override
    protected RequestBody finalizeData()
    {
        DataObject object = DataObject.empty();
        object.put("type", isRole() ? "role" : "member");
        object.put("allow", allowSet ? allow : getCurrentAllow());
        object.put("deny", denySet ? deny : getCurrentDeny());
        reset();
        return getRequestBody(object);
    }

    @Override
    protected void handleSuccess(Response response, Request<PermissionOverride> request)
    {
        DataObject object = (DataObject) request.getRawBody();
        PermissionOverrideImpl override = new PermissionOverrideImpl(channel, id, isRole());
        override.setAllow(object.getLong("allow"));
        override.setDeny(object.getLong("deny"));
        //((AbstractChannelImpl<?,?>) channel).getOverrideMap().put(id, override); This is added by the event later
        request.onSuccess(override);
    }
}
