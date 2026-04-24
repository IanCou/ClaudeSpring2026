package com.voiceannounce;

import com.voiceannounce.handler.GameTickHandler;
import com.voiceannounce.handler.KeyInputHandler;
import com.voiceannounce.handler.MacroHandler;
import com.voiceannounce.handler.MineBlocksHandler;
import com.voiceannounce.handler.RenderOverlayHandler;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Mod(
    modid = VoiceAnnounce.MOD_ID,
    name = VoiceAnnounce.MOD_NAME,
    version = VoiceAnnounce.VERSION,
    clientSideOnly = true,
    acceptedMinecraftVersions = "[1.12,1.13)"
)
public class VoiceAnnounce {

    public static final String MOD_ID = "voiceannounce";
    public static final String MOD_NAME = "Voice Command";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static String whisperBin;
    public static String modelPath;

    private static GeminiClient geminiClient;
    private static InputState inputState;
    private static CommandQueue commandQueue;

    @Mod.Instance
    public static VoiceAnnounce instance;

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void preInit(FMLPreInitializationEvent event) {
        ClientRegistry.registerKeyBinding(KeyInputHandler.KEY_SPEAK);
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        inputState = new InputState();
        commandQueue = new CommandQueue(inputState);

        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
        MinecraftForge.EVENT_BUS.register(new RenderOverlayHandler());
        MinecraftForge.EVENT_BUS.register(new GameTickHandler(commandQueue));
        MinecraftForge.EVENT_BUS.register(new MineBlocksHandler());
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void postInit(FMLPostInitializationEvent event) {
        File gameDir = Minecraft.getMinecraft().gameDir;
        whisperBin = findWhisperBinary();
        modelPath = findModel(gameDir);

        if (whisperBin == null) {
            LOGGER.error("[VoiceAnnounce] whisper-cli not found. Install: brew install whisper-cpp");
        } else {
            LOGGER.info("[VoiceAnnounce] whisper-cli: {}", whisperBin);
        }

        if (modelPath == null) {
            LOGGER.error("[VoiceAnnounce] No GGML model found. Drop ggml-base.en.bin in {}", gameDir);
        } else {
            LOGGER.info("[VoiceAnnounce] model: {}", modelPath);
        }

        MacroHandler.loadFromGameDir(gameDir);
        com.voiceannounce.handler.TriggerMatcher.loadFromGameDir(gameDir);

        String apiKey = resolveApiKey(gameDir);
        if (apiKey == null || apiKey.isEmpty()) {
            LOGGER.error("[VoiceAnnounce] No GEMINI_API_KEY found.");
            LOGGER.error("[VoiceAnnounce] Set env var GEMINI_API_KEY or create a file gemini.key in {}", gameDir);
        } else {
            geminiClient = new GeminiClient(apiKey);
            LOGGER.info("[VoiceAnnounce] Gemini client initialized.");
        }
    }

    public static GeminiClient getClient() { return geminiClient; }
    public static CommandQueue getQueue() { return commandQueue; }
    public static InputState getInputState() { return inputState; }

    private static String resolveApiKey(File gameDir) {
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        File keyFile = new File(gameDir, "gemini.key");
        if (keyFile.exists()) {
            try {
                return new String(Files.readAllBytes(keyFile.toPath()), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                LOGGER.error("[VoiceAnnounce] Failed to read gemini.key: {}", e.getMessage());
            }
        }
        return null;
    }

    private static String findWhisperBinary() {
        String[] candidates = {
            "/opt/homebrew/bin/whisper-cli",
            "/usr/local/bin/whisper-cli",
            "/usr/bin/whisper-cli",
            "whisper-cli"
        };
        for (String c : candidates) {
            try {
                File f = new File(c);
                if (f.isAbsolute() && f.canExecute()) return c;
                Process p = Runtime.getRuntime().exec(new String[]{"which", c});
                if (p.waitFor() == 0) return c;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String findModel(File gameDir) {
        String[] names = {
            "ggml-base.en.bin", "ggml-small.en.bin", "ggml-medium.en.bin",
            "ggml-tiny.en.bin", "ggml-large-v3.bin"
        };
        for (String n : names) {
            File f = new File(gameDir, n);
            if (f.exists()) return f.getAbsolutePath();
        }
        File[] bins = gameDir.listFiles(f ->
            f.getName().startsWith("ggml") && f.getName().endsWith(".bin"));
        if (bins != null && bins.length > 0) return bins[0].getAbsolutePath();
        return null;
    }
}
