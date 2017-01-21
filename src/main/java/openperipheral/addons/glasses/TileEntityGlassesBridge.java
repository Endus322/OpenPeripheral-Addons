package openperipheral.addons.glasses;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.mojang.authlib.GameProfile;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.ForgeDirection;
import openmods.api.ICustomHarvestDrops;
import openmods.api.IPlaceAwareTile;
import openmods.include.IncludeInterface;
import openmods.include.IncludeOverride;
import openmods.network.event.NetworkEventManager;
import openmods.network.senders.ITargetedPacketSender;
import openmods.tileentity.OpenTileEntity;
import openmods.utils.ItemUtils;
import openperipheral.addons.glasses.GlassesEvent.GlassesClientEvent;
import openperipheral.addons.glasses.drawable.Drawable;
import openperipheral.addons.glasses.server.DrawableContainerMaster;
import openperipheral.addons.glasses.server.IDrawableFactory;
import openperipheral.addons.glasses.server.SurfaceServer;
import openperipheral.addons.glasses.server.TerminalManagerServer;
import openperipheral.api.adapter.Asynchronous;
import openperipheral.api.adapter.Doc;
import openperipheral.api.adapter.method.Arg;
import openperipheral.api.adapter.method.ReturnType;
import openperipheral.api.adapter.method.ScriptCallable;
import openperipheral.api.architecture.FeatureGroup;
import openperipheral.api.architecture.IArchitectureAccess;
import openperipheral.api.architecture.IAttachable;
import openperipheral.api.peripheral.PeripheralTypeId;

@Doc({ "This peripheral is used to control terminal glasses and wireless keyboard.",
		"There is one global surface and one private surface for every glasses user.",
		"All calls names .add*() will return object that can be later used to modify it.",
		"To make changes visible to players, call .sync().",
		"This peripheral signals few events. Full list available here: http://goo.gl/8Hf2yA",
		"Simple demo: http://goo.gl/n5HPN8" })
@PeripheralTypeId("openperipheral_bridge")
@FeatureGroup("openperipheral-glasses")
public class TileEntityGlassesBridge extends OpenTileEntity implements IAttachable, IPlaceAwareTile, ICustomHarvestDrops, IClearable {

	private static final String GLOBAL_FAKE_PLAYER_NAME = "$GLOBAL$";

	public static final String TAG_GUID = "guid";

	private static final String EVENT_PLAYER_ATTACH = "glasses_attach";

	private static final String EVENT_PLAYER_DETACH = "glasses_detach";

	private static class PlayerInfo {
		public final GameProfile profile;
		public final WeakReference<EntityPlayerMP> player;
		public final SurfaceServer surface;

		public PlayerInfo(long guid, EntityPlayerMP player) {
			this.player = new WeakReference<EntityPlayerMP>(player);
			this.profile = player.getGameProfile();
			this.surface = SurfaceServer.createPrivateSurface(guid);
		}
	}

	private final Map<UUID, PlayerInfo> knownPlayersByUUID = Maps.newHashMap();
	private final Map<String, PlayerInfo> knownPlayersByName = Maps.newHashMap();
	private List<Object> globalFullDataPackets;

	private Set<IArchitectureAccess> computers = Sets.newIdentityHashSet();

	private Optional<Long> guid = Optional.absent();

	private SurfaceServer globalSurface;

	@IncludeInterface(IContainer.class)
	private IContainer<Drawable> getDrawablesContainer() {
		return globalSurface.drawablesContainer;
	}

	@IncludeInterface(IDrawableFactory.class)
	private IDrawableFactory getDrawablesFactory() {
		return globalSurface.drawablesFactory;
	}

	public TileEntityGlassesBridge() {}

	private void rebuildPlayerNamesMap() {
		knownPlayersByName.clear();

		for (PlayerInfo info : knownPlayersByUUID.values()) {
			final EntityPlayerMP player = info.player.get();
			if (isPlayerValid(player)) {
				String name = player.getGameProfile().getName();
				knownPlayersByName.put(name, info);
			}
		}
	}

	public long getOrCreateGuid() {
		if (this.guid.isPresent())
			return this.guid.get();

		final long newGuid = TerminalUtils.generateGuid();
		this.guid = Optional.of(newGuid);
		return newGuid;
	}

	public void registerTerminal(EntityPlayerMP player) {
		if (!knownPlayersByUUID.containsKey(player.getGameProfile().getId())) {
			final PlayerInfo playerInfo = new PlayerInfo(getOrCreateGuid(), player);
			final GameProfile gameProfile = player.getGameProfile();

			knownPlayersByUUID.put(gameProfile.getId(), playerInfo);
			rebuildPlayerNamesMap();
			queueEvent(EVENT_PLAYER_ATTACH, player);

			sentStoredFullDataToPlayer(player);
		}
	}

	private void sentStoredFullDataToPlayer(EntityPlayer player) {
		if (globalFullDataPackets != null) NetworkEventManager.INSTANCE.dispatcher().senders.player.sendMessages(globalFullDataPackets, player);
	}

	private static interface IEventArgsSource {
		public Object[] getArgs(IArchitectureAccess access);
	}

	private void queueEvent(String event, EntityPlayer user, IEventArgsSource source) {
		final GameProfile gameProfile = user.getGameProfile();
		final UUID userId = gameProfile.getId();
		final String idString = userId != null? userId.toString() : null;
		final String userName = gameProfile.getName();

		for (IArchitectureAccess computer : computers) {
			final Object[] extra = source.getArgs(computer);
			final Object[] args = new Object[3 + extra.length];
			System.arraycopy(extra, 0, args, 3, extra.length);
			args[0] = computer.peripheralName();
			args[1] = userName;
			args[2] = idString;

			computer.signal(event, args);
		}
	}

	private void queueEvent(String event, EntityPlayer user, final Object... args) {
		queueEvent(event, user, new IEventArgsSource() {
			@Override
			public Object[] getArgs(IArchitectureAccess access) {
				return args;
			}
		});
	}

	public void onChatCommand(String event, String content, EntityPlayer player) {
		queueEvent(event, player, content);
	}

	@Override
	public void updateEntity() {
		super.updateEntity();
		if (worldObj.isRemote) return;

		final long guid = getOrCreateGuid();

		if (globalSurface == null) globalSurface = SurfaceServer.createPublicSurface(guid);
		TerminalManagerServer.instance.registerBridge(guid, this);

		boolean playersRemoved = false;
		Iterator<PlayerInfo> it = knownPlayersByUUID.values().iterator();
		while (it.hasNext()) {
			final PlayerInfo info = it.next();
			final EntityPlayerMP player = info.player.get();

			if (!isPlayerValid(player)) {
				queueEvent(EVENT_PLAYER_DETACH, player);
				sendClearPacketToPlayer(player, globalSurface);
				sendClearPacketToPlayer(player, info.surface);
				it.remove();
				playersRemoved = true;
			}
		}

		if (playersRemoved) rebuildPlayerNamesMap();
	}

	private static void sendClearPacketToPlayer(EntityPlayerMP player, SurfaceServer surface) {
		surface.drawablesContainer.createClearPacket().sendToPlayer(player);
	}

	private boolean isPlayerValid(EntityPlayerMP player) {
		if (player == null) return false;

		if (player.isDead && !isPlayerLogged(player)) return false;

		final Optional<Long> guid = TerminalIdAccess.instance.getIdFrom(player);
		return guid.isPresent() && guid.get() == getOrCreateGuid();
	}

	private static boolean isPlayerLogged(EntityPlayerMP player) {
		final GameProfile gameProfile = player.getGameProfile();
		@SuppressWarnings("unchecked")
		List<EntityPlayerMP> players = MinecraftServer.getServer().getConfigurationManager().playerEntityList;
		for (EntityPlayerMP p : players) {
			if (p.getGameProfile().equals(gameProfile)) return true;
		}

		return false;
	}

	@Override
	public void writeToNBT(NBTTagCompound tag) {
		super.writeToNBT(tag);
		if (guid.isPresent()) tag.setLong(TAG_GUID, guid.get());
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		this.guid = Optional.fromNullable(TerminalUtils.extractGuid(tag));
	}

	@Override
	public void onBlockPlacedBy(EntityPlayer player, ForgeDirection side, ItemStack stack, float hitX, float hitY, float hitZ) {
		NBTTagCompound tag = stack.getTagCompound();
		if (tag != null && tag.hasKey(TAG_GUID)) this.guid = Optional.of(tag.getLong(TAG_GUID));
	}

	@Override
	public void addHarvestDrops(EntityPlayer player, List<ItemStack> drops) {
		ItemStack result = new ItemStack(getBlockType());
		if (guid.isPresent()) {
			NBTTagCompound tag = ItemUtils.getItemTag(result);
			tag.setLong(TAG_GUID, guid.get());
		}
		drops.add(result);
	}

	@Override
	public boolean suppressNormalHarvestDrops() {
		return true;
	}

	@Override
	public void addComputer(IArchitectureAccess computer) {
		if (!computers.contains(computer)) {
			computers.add(computer);
		}
	}

	@Override
	public void removeComputer(IArchitectureAccess computer) {
		computers.remove(computer);
	}

	public void handleUserEvent(final GlassesClientEvent evt) {
		queueEvent(evt.getEventName(), evt.sender, new IEventArgsSource() {
			@Override
			public Object[] getArgs(IArchitectureAccess access) {
				return evt.getEventArgs(access);
			}
		});
	}

	public SurfaceServer getSurface(String username) {
		if (GLOBAL_FAKE_PLAYER_NAME.equals(username)) return globalSurface;
		PlayerInfo info = knownPlayersByName.get(username);
		return info != null? info.surface : null;
	}

	public SurfaceServer getSurface(UUID uuid) {
		if (TerminalUtils.GLOBAL_SURFACE_UUID.equals(uuid)) return globalSurface;
		PlayerInfo info = knownPlayersByUUID.get(uuid);
		return info != null? info.surface : null;
	}

	// never, ever make this asynchronous
	@ScriptCallable(description = "Send updates to client. Without it changes won't be visible", name = "sync")
	public void syncContents() {
		final DrawableContainerMaster drawables = globalSurface.drawablesContainer;
		synchronized (drawables) {
			final boolean globalChanged = drawables.hasUpdates();

			if (globalChanged || globalFullDataPackets == null) {
				final TerminalEvent.Data globalFullData = drawables.createFullDataEvent();
				globalFullDataPackets = globalFullData.serialize();
			}

			List<Object> globalUpdateDataPackets = null;

			if (globalChanged) {
				final TerminalEvent.Data globalDelta = drawables.createUpdateDataEvent();
				globalUpdateDataPackets = globalDelta.serialize();
			}

			final ITargetedPacketSender<EntityPlayer> playerSender = NetworkEventManager.INSTANCE.dispatcher().senders.player;

			for (PlayerInfo info : knownPlayersByUUID.values()) {
				final EntityPlayerMP player = info.player.get();
				if (isPlayerValid(player)) {
					if (globalUpdateDataPackets != null) playerSender.sendMessages(globalUpdateDataPackets, player);
					sendPrivateUpdateToPlayer(player, info);
				}
			}
		}
	}

	private static void sendPrivateUpdateToPlayer(EntityPlayerMP player, PlayerInfo info) {
		final SurfaceServer privateSurface = info.surface;
		final DrawableContainerMaster drawables = privateSurface.drawablesContainer;
		synchronized (drawables) {
			if (drawables.hasUpdates()) {
				final TerminalEvent.Data privateUpdateDataPackets = drawables.createUpdateDataEvent();
				privateUpdateDataPackets.sendToPlayer(player);
			}
		}
	}

	private static void sendFullDataPacketToPlayer(EntityPlayer player, final SurfaceServer surface) {
		surface.drawablesContainer.createFullDataEvent().sendToPlayer(player);
	}

	private void sendPrivateFullDataToPlayer(EntityPlayer player) {
		UUID playerUuid = player.getGameProfile().getId();
		PlayerInfo info = knownPlayersByUUID.get(playerUuid);
		if (info != null) sendFullDataPacketToPlayer(player, info.surface);
	}

	public void handlePrivateDrawableResetRequest(TerminalEvent.PrivateDrawableReset evt) {
		sendPrivateFullDataToPlayer(evt.sender);
	}

	public void handlePublicDrawableResetRequest(TerminalEvent.PublicDrawableReset evt) {
		sentStoredFullDataToPlayer(evt.sender);
	}

	@Asynchronous
	@ScriptCallable(returnTypes = ReturnType.TABLE, description = "Get the names of all the users linked up to this bridge")
	public List<GameProfile> getUsers() {
		List<GameProfile> result = Lists.newArrayList();
		for (PlayerInfo info : knownPlayersByUUID.values())
			result.add(info.profile);

		return result;
	}

	@Asynchronous
	@ScriptCallable(returnTypes = ReturnType.STRING, name = "getGuid", description = "Get the Guid of this bridge")
	public String getGuidString() {
		return TerminalUtils.formatTerminalId(getOrCreateGuid());
	}

	@Override
	@IncludeOverride
	public void clear() {
		globalSurface.drawablesContainer.clear();

		for (PlayerInfo info : knownPlayersByUUID.values())
			info.surface.drawablesContainer.clear();
	}

	@Asynchronous
	@ScriptCallable(returnTypes = ReturnType.OBJECT, description = "Get the surface of a user to draw privately on their screen")
	public SurfaceServer getSurfaceByName(@Arg(name = "username", description = "The username of the user to get the draw surface for") String username) {
		SurfaceServer playerSurface = getSurface(username);
		Preconditions.checkNotNull(playerSurface, "Invalid player");
		return playerSurface;
	}

	@Asynchronous
	@ScriptCallable(returnTypes = ReturnType.OBJECT, description = "Get the surface of a user to draw privately on their screen")
	public SurfaceServer getSurfaceByUUID(@Arg(name = "uuid", description = "The uuid of the user to get the draw surface for") UUID uuid) {
		SurfaceServer playerSurface = getSurface(uuid);
		Preconditions.checkNotNull(playerSurface, "Invalid player");
		return playerSurface;
	}

	@Asynchronous
	@ScriptCallable(returnTypes = ReturnType.OBJECT, description = "Returns object used for controlling player capture mode")
	public GuiCaptureControl getCaptureControl(@Arg(name = "uuid") UUID uuid) {
		PlayerInfo info = knownPlayersByUUID.get(uuid);
		return info != null? new GuiCaptureControl(getOrCreateGuid(), info.player) : null;
	}
}
