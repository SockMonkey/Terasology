/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.engine;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.asset.AssetFactory;
import org.terasology.asset.AssetManager;
import org.terasology.asset.AssetType;
import org.terasology.asset.AssetUri;
import org.terasology.config.Config;
import org.terasology.engine.bootstrap.ApplyModulesUtil;
import org.terasology.engine.modes.GameState;
import org.terasology.engine.module.EngineModulePolicy;
import org.terasology.engine.module.ModuleManager;
import org.terasology.engine.module.ModuleManagerImpl;
import org.terasology.engine.module.ModuleSecurityManager;
import org.terasology.engine.paths.PathManager;
import org.terasology.engine.subsystem.DisplayDevice;
import org.terasology.engine.subsystem.EngineSubsystem;
import org.terasology.engine.subsystem.RenderingSubsystemFactory;
import org.terasology.entitySystem.prefab.Prefab;
import org.terasology.entitySystem.prefab.PrefabData;
import org.terasology.entitySystem.prefab.internal.PojoPrefab;
import org.terasology.game.Game;
import org.terasology.identity.CertificateGenerator;
import org.terasology.identity.CertificatePair;
import org.terasology.input.InputSystem;
import org.terasology.logic.behavior.asset.BehaviorTree;
import org.terasology.logic.behavior.asset.BehaviorTreeData;
import org.terasology.monitoring.PerformanceMonitor;
import org.terasology.monitoring.ThreadActivity;
import org.terasology.monitoring.ThreadMonitor;
import org.terasology.monitoring.gui.AdvancedMonitor;
import org.terasology.network.NetworkSystem;
import org.terasology.network.internal.NetworkSystemImpl;
import org.terasology.persistence.typeHandling.TypeSerializationLibrary;
import org.terasology.physics.CollisionGroupManager;
import org.terasology.reflection.copy.CopyStrategyLibrary;
import org.terasology.reflection.reflect.ReflectFactory;
import org.terasology.reflection.reflect.ReflectionReflectFactory;
import org.terasology.registry.CoreRegistry;
import org.terasology.rendering.nui.asset.UIData;
import org.terasology.rendering.nui.asset.UIElement;
import org.terasology.rendering.nui.skin.UISkin;
import org.terasology.rendering.nui.skin.UISkinData;
import org.terasology.utilities.concurrency.ShutdownTask;
import org.terasology.utilities.concurrency.Task;
import org.terasology.utilities.concurrency.TaskMaster;
import org.terasology.version.TerasologyVersion;
import org.terasology.world.block.shapes.BlockShape;
import org.terasology.world.block.shapes.BlockShapeData;
import org.terasology.world.block.shapes.BlockShapeImpl;
import org.terasology.world.generator.internal.WorldGeneratorManager;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ReflectPermission;
import java.nio.file.Files;
import java.security.Policy;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author Immortius
 */
public class TerasologyEngine implements GameEngine {

    private static final Logger logger = LoggerFactory.getLogger(TerasologyEngine.class);

    private GameState currentState;
    private boolean initialised;
    private boolean running;
    private boolean disposed;
    private GameState pendingState;

    private Config config;

    private EngineTime time;
    private final TaskMaster<Task> commonThreadPool = TaskMaster.createFIFOTaskMaster("common", 16);

    private boolean hibernationAllowed;
    private boolean gameFocused = true;
    private Set<StateChangeSubscriber> stateChangeSubscribers = Sets.newLinkedHashSet();

    private Deque<EngineSubsystem> subsystems;

    public TerasologyEngine(Collection<EngineSubsystem> subsystems) {
        this.subsystems = Queues.newArrayDeque(subsystems);
    }

    public Iterable<EngineSubsystem> getSubsystems() {
        return subsystems;
    }

    @Override
    public void init() {
        if (initialised) {
            return;
        }

        Stopwatch sw = Stopwatch.createStarted();

        try {
            logger.info("Initializing Terasology...");
            logger.info(TerasologyVersion.getInstance().toString());
            logger.info("Platform: {}", System.getProperty("os.name"));
            logger.info("Home path: {}", PathManager.getInstance().getHomePath());
            logger.info("Install path: {}", PathManager.getInstance().getInstallPath());
            logger.info("Java version: {}", System.getProperty("java.version"));

            initConfig();

            for (EngineSubsystem subsystem : getSubsystems()) {
                subsystem.preInitialise();
            }

            // Verify required systems are available
            time = (EngineTime) CoreRegistry.get(Time.class);
            if (time == null) {
                throw new IllegalStateException("Time not registered as a core system.");
            }

            initManagers();

            for (EngineSubsystem subsystem : getSubsystems()) {
                subsystem.postInitialise(config);
            }

            // Verify required systems are available
            if (CoreRegistry.get(DisplayDevice.class) == null) {
                throw new IllegalStateException("DisplayDevice not registered as a core system.");
            }
            if (CoreRegistry.get(RenderingSubsystemFactory.class) == null) {
                throw new IllegalStateException("EngineSubsystemFactory not registered as a core system.");
            }
            if (CoreRegistry.get(InputSystem.class) == null) {
                throw new IllegalStateException("InputSystem not registered as a core system.");
            }

            initAssets();

            if (config.getSystem().isMonitoringEnabled()) {
                new AdvancedMonitor().setVisible(true);
            }
            initialised = true;
        } catch (Throwable t) {
            logger.error("Failed to initialise Terasology", t);
            throw new RuntimeException("Failed to initialise Terasology", t);
        }

        logger.info("Initialization completed in {}sec.", 0.01 * (sw.elapsed(TimeUnit.MILLISECONDS) / 10)); // round to 2 digits
    }

    private void initAssets() {
        AssetManager assetManager = CoreRegistry.get(AssetManager.class);
        assetManager.setAssetFactory(AssetType.PREFAB, new AssetFactory<PrefabData, Prefab>() {

            @Override
            public Prefab buildAsset(AssetUri uri, PrefabData data) {
                return new PojoPrefab(uri, data);
            }
        });
        assetManager.setAssetFactory(AssetType.SHAPE, new AssetFactory<BlockShapeData, BlockShape>() {

            @Override
            public BlockShape buildAsset(AssetUri uri, BlockShapeData data) {
                return new BlockShapeImpl(uri, data);
            }
        });
        assetManager.setAssetFactory(AssetType.UI_SKIN, new AssetFactory<UISkinData, UISkin>() {
            @Override
            public UISkin buildAsset(AssetUri uri, UISkinData data) {
                return new UISkin(uri, data);
            }
        });
        assetManager.setAssetFactory(AssetType.BEHAVIOR, new AssetFactory<BehaviorTreeData, BehaviorTree>() {
            @Override
            public BehaviorTree buildAsset(AssetUri uri, BehaviorTreeData data) {
                return new BehaviorTree(uri, data);
            }
        });
        assetManager.setAssetFactory(AssetType.UI_ELEMENT, new AssetFactory<UIData, UIElement>() {
            @Override
            public UIElement buildAsset(AssetUri uri, UIData data) {
                return new UIElement(uri, data);
            }
        });

    }

    private void initConfig() {
        if (Files.isRegularFile(Config.getConfigFile())) {
            try {
                config = Config.load(Config.getConfigFile());
            } catch (IOException e) {
                logger.error("Failed to load config", e);
                config = new Config();
            }
        } else {
            config = new Config();
        }
        if (!config.getDefaultModSelection().hasModule("core")) {
            config.getDefaultModSelection().addModule("core");
        }
        if (config.getSecurity().getServerPrivateCertificate() == null) {
            CertificateGenerator generator = new CertificateGenerator();
            CertificatePair serverIdentity = generator.generateSelfSigned();
            config.getSecurity().setServerCredentials(serverIdentity.getPublicCert(), serverIdentity.getPrivateCert());
            config.save();
        }
        logger.info("Video Settings: " + config.getRendering().toString());
        CoreRegistry.putPermanently(Config.class, config);
    }

    @Override
    public void run(GameState initialState) {
        try {
            CoreRegistry.putPermanently(GameEngine.class, this);
            if (!initialised) {
                init();
            }
            changeState(initialState);
            running = true;
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);

            mainLoop();

            cleanup();
        } catch (Throwable t) {
            logger.error("Uncaught exception", t);
            throw new RuntimeException("Uncaught exception", t);
        }
    }

    @Override
    public void shutdown() {
        running = false;
    }

    @Override
    public void dispose() {
        try {
            if (!running) {
                disposed = true;
                initialised = false;
                Iterator<EngineSubsystem> iter = subsystems.descendingIterator();
                while (iter.hasNext()) {
                    EngineSubsystem subsystem = iter.next();
                    subsystem.dispose();
                }
            }
        } catch (Throwable t) {
            logger.error("Uncaught exception", t);
            throw new RuntimeException("Uncaught exception", t);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public GameState getState() {
        return currentState;
    }

    @Override
    public void changeState(GameState newState) {
        if (currentState != null) {
            pendingState = newState;
        } else {
            switchState(newState);
        }
    }

    @Override
    public void submitTask(final String name, final Runnable task) {
        try {
            commonThreadPool.put(new Task() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public void run() {
                    Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                    Thread.currentThread().setName("Engine-Task-Pool");
                    try (ThreadActivity ignored = ThreadMonitor.startThreadActivity(task.getClass().getSimpleName())) {
                        task.run();
                    } catch (RejectedExecutionException e) {
                        ThreadMonitor.addError(e);
                        logger.error("Thread submitted after shutdown requested: {}", name);
                    } catch (Throwable e) {
                        ThreadMonitor.addError(e);
                    }
                }

                @Override
                public boolean isTerminateSignal() {
                    return false;
                }
            });
        } catch (InterruptedException e) {
            logger.error("Failed to submit task {}, running on main thread", name, e);
            task.run();
        }
    }

    private void initManagers() {
        GameThread.setGameThread();
        ModuleManager moduleManager = initModuleManager();

        ReflectFactory reflectFactory = CoreRegistry.putPermanently(ReflectFactory.class, new ReflectionReflectFactory());
        CopyStrategyLibrary copyStrategyLibrary = CoreRegistry.putPermanently(CopyStrategyLibrary.class, new CopyStrategyLibrary(reflectFactory));

        CoreRegistry.putPermanently(TypeSerializationLibrary.class, new TypeSerializationLibrary(reflectFactory, copyStrategyLibrary));

        AssetManager assetManager = CoreRegistry.putPermanently(AssetManager.class, new AssetManager(moduleManager));
        CoreRegistry.putPermanently(CollisionGroupManager.class, new CollisionGroupManager());
        CoreRegistry.putPermanently(WorldGeneratorManager.class, new WorldGeneratorManager());
        CoreRegistry.putPermanently(ComponentSystemManager.class, new ComponentSystemManager());
        CoreRegistry.putPermanently(NetworkSystem.class, new NetworkSystemImpl(time));
        CoreRegistry.putPermanently(Game.class, new Game(this, time));

        AssetType.registerAssetTypes(assetManager);
        assetManager.addAssetSource(moduleManager.getActiveModule(TerasologyConstants.ENGINE_MODULE).getModuleSource());
        ApplyModulesUtil.applyModules();
    }

    private ModuleManager initModuleManager() {
        ModuleSecurityManager moduleSecurityManager = new ModuleSecurityManager();
        ModuleManager moduleManager = CoreRegistry.putPermanently(ModuleManager.class,
                new ModuleManagerImpl(moduleSecurityManager, config.getSystem().isReflectionsCacheEnabled()));

        moduleSecurityManager.addAPIPackage("java.lang");
        moduleSecurityManager.addAPIPackage("java.lang.ref");
        moduleSecurityManager.addAPIPackage("java.math");
        moduleSecurityManager.addAPIPackage("java.util");
        moduleSecurityManager.addAPIPackage("java.util.concurrent");
        moduleSecurityManager.addAPIPackage("java.util.concurrent.atomic");
        moduleSecurityManager.addAPIPackage("java.util.concurrent.locks");
        moduleSecurityManager.addAPIPackage("java.util.regex");
        moduleSecurityManager.addAPIPackage("java.awt");
        moduleSecurityManager.addAPIPackage("java.awt.geom");
        moduleSecurityManager.addAPIPackage("java.awt.image");
        moduleSecurityManager.addAPIPackage("com.google.common.annotations");
        moduleSecurityManager.addAPIPackage("com.google.common.cache");
        moduleSecurityManager.addAPIPackage("com.google.common.collect");
        moduleSecurityManager.addAPIPackage("com.google.common.base");
        moduleSecurityManager.addAPIPackage("com.google.common.math");
        moduleSecurityManager.addAPIPackage("com.google.common.primitives");
        moduleSecurityManager.addAPIPackage("com.google.common.util.concurrent");
        moduleSecurityManager.addAPIPackage("gnu.trove");
        moduleSecurityManager.addAPIPackage("gnu.trove.decorator");
        moduleSecurityManager.addAPIPackage("gnu.trove.function");
        moduleSecurityManager.addAPIPackage("gnu.trove.iterator");
        moduleSecurityManager.addAPIPackage("gnu.trove.iterator.hash");
        moduleSecurityManager.addAPIPackage("gnu.trove.list");
        moduleSecurityManager.addAPIPackage("gnu.trove.list.array");
        moduleSecurityManager.addAPIPackage("gnu.trove.list.linked");
        moduleSecurityManager.addAPIPackage("gnu.trove.map");
        moduleSecurityManager.addAPIPackage("gnu.trove.map.hash");
        moduleSecurityManager.addAPIPackage("gnu.trove.map.custom_hash");
        moduleSecurityManager.addAPIPackage("gnu.trove.procedure");
        moduleSecurityManager.addAPIPackage("gnu.trove.procedure.array");
        moduleSecurityManager.addAPIPackage("gnu.trove.queue");
        moduleSecurityManager.addAPIPackage("gnu.trove.set");
        moduleSecurityManager.addAPIPackage("gnu.trove.set.hash");
        moduleSecurityManager.addAPIPackage("gnu.trove.stack");
        moduleSecurityManager.addAPIPackage("gnu.trove.stack.array");
        moduleSecurityManager.addAPIPackage("gnu.trove.strategy");
        moduleSecurityManager.addAPIPackage("javax.vecmath");
        moduleSecurityManager.addAPIPackage("com.yourkit.runtime");
        moduleSecurityManager.addAPIPackage("com.bulletphysics.linearmath");
        moduleSecurityManager.addAPIClass(com.esotericsoftware.reflectasm.MethodAccess.class);
        moduleSecurityManager.addAPIClass(IOException.class);
        moduleSecurityManager.addAPIClass(InvocationTargetException.class);
        moduleSecurityManager.addAPIClass(LoggerFactory.class);
        moduleSecurityManager.addAPIClass(Logger.class);
        for (Class<?> apiClass : moduleManager.getActiveModuleReflections().getTypesAnnotatedWith(API.class)) {
            if (apiClass.isSynthetic()) {
                // This is a package-info
                moduleSecurityManager.addAPIPackage(apiClass.getPackage().getName());
            } else {
                moduleSecurityManager.addAPIClass(apiClass);
            }
        }

        moduleSecurityManager.addFullPrivilegePackage("ch.qos.logback.classic");
        moduleSecurityManager.addAllowedPermission("com.google.gson", ReflectPermission.class);
        moduleSecurityManager.addAllowedPermission("com.google.gson.internal", ReflectPermission.class);

        moduleSecurityManager.addAPIClass(java.nio.ByteBuffer.class);
        moduleSecurityManager.addAPIClass(java.nio.IntBuffer.class);

        Policy.setPolicy(new EngineModulePolicy());
        System.setSecurityManager(moduleSecurityManager);
        return moduleManager;
    }

    private void cleanup() {
        logger.info("Shutting down Terasology...");

        Iterator<EngineSubsystem> iter = subsystems.descendingIterator();
        while (iter.hasNext()) {
            EngineSubsystem subsystem = iter.next();
            subsystem.shutdown(config);
        }

        config.save();
        if (currentState != null) {
            currentState.dispose();
            currentState = null;
        }

        stopThreads();
    }

    public void stopThreads() {
        commonThreadPool.shutdown(new ShutdownTask(), false);
    }

    public void restartThreads() {
        commonThreadPool.restart();
    }

    private void mainLoop() {
        NetworkSystem networkSystem = CoreRegistry.get(NetworkSystem.class);

        DisplayDevice display = CoreRegistry.get(DisplayDevice.class);

        PerformanceMonitor.startActivity("Other");
        // MAIN GAME LOOP
        while (running && !display.isCloseRequested()) {

            // Only process rendering and updating once a second
            if (!display.isActive() && isHibernationAllowed()) {
                time.setPaused(true);
                Iterator<Float> updateCycles = time.tick();
                while (updateCycles.hasNext()) {
                    updateCycles.next();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    logger.warn("Display inactivity sleep interrupted", e);
                }

                display.processMessages();
                time.setPaused(false);
                continue;
            }

            processStateChanges();

            if (currentState == null) {
                shutdown();
                break;
            }

            Iterator<Float> updateCycles = time.tick();

            PerformanceMonitor.startActivity("Network Update");
            networkSystem.update();
            PerformanceMonitor.endActivity();

            long totalDelta = 0;
            while (updateCycles.hasNext()) {
                float delta = updateCycles.next();
                totalDelta += time.getDeltaInMs();
                PerformanceMonitor.startActivity("Main Update");
                currentState.update(delta);
                PerformanceMonitor.endActivity();
            }

            float delta = totalDelta / 1000f;

            for (EngineSubsystem subsystem : getSubsystems()) {
                PerformanceMonitor.startActivity(subsystem.getClass().getSimpleName());
                subsystem.preUpdate(currentState, delta);
                PerformanceMonitor.endActivity();
            }

            GameThread.processWaitingProcesses();

            for (EngineSubsystem subsystem : getSubsystems()) {
                PerformanceMonitor.startActivity(subsystem.getClass().getSimpleName());
                subsystem.postUpdate(currentState, delta);
                PerformanceMonitor.endActivity();
            }

            PerformanceMonitor.rollCycle();
            PerformanceMonitor.startActivity("Other");
        }
        PerformanceMonitor.endActivity();
        running = false;
    }

    private void processStateChanges() {
        if (pendingState != null) {
            switchState(pendingState);
            pendingState = null;
        }
    }

    private void switchState(GameState newState) {
        if (currentState != null) {
            currentState.dispose();
        }
        currentState = newState;
        newState.init(this);
        for (StateChangeSubscriber subscriber : stateChangeSubscribers) {
            subscriber.onStateChange();
        }
        // drain input queues
        InputSystem inputSystem = CoreRegistry.get(InputSystem.class);
        inputSystem.getMouseDevice().getInputQueue();
        inputSystem.getKeyboard().getInputQueue();
    }

    public boolean isFullscreen() {
        return config.getRendering().isFullscreen();
    }

    public void setFullscreen(boolean state) {
        if (config.getRendering().isFullscreen() != state) {
            config.getRendering().setFullscreen(state);
            DisplayDevice display = CoreRegistry.get(DisplayDevice.class);
            display.setFullscreen(state);
        }
    }

    public boolean isHibernationAllowed() {
        return hibernationAllowed && currentState.isHibernationAllowed();
    }

    public void setHibernationAllowed(boolean allowed) {
        this.hibernationAllowed = allowed;
    }

    public boolean hasFocus() {
        DisplayDevice display = CoreRegistry.get(DisplayDevice.class);
        return gameFocused && display.isActive();
    }

    @Override
    public boolean hasMouseFocus() {
        return gameFocused;
    }

    public void setFocus(boolean focused) {
        gameFocused = focused;
    }

    @Override
    public void subscribeToStateChange(StateChangeSubscriber subscriber) {
        stateChangeSubscribers.add(subscriber);
    }

    @Override
    public void unsubscribeToStateChange(StateChangeSubscriber subscriber) {
        stateChangeSubscribers.remove(subscriber);
    }
}
