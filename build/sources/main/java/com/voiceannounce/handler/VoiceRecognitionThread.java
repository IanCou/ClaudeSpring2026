package com.voiceannounce.handler;

import com.google.gson.JsonObject;
import com.voiceannounce.ChainValidator;
import com.voiceannounce.GeminiClient;
import com.voiceannounce.PlayerState;
import com.voiceannounce.ToolCall;
import com.voiceannounce.ToolResult;
import com.voiceannounce.VoiceAnnounce;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@SideOnly(Side.CLIENT)
public class VoiceRecognitionThread {

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat(16000, 16, 1, true, false);
    private static final AtomicBoolean capturing = new AtomicBoolean(false);
    private static Thread captureThread;
    private static final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

    public static void startCapture() {
        if (VoiceAnnounce.whisperBin == null || VoiceAnnounce.modelPath == null) {
            VoiceAnnounce.LOGGER.warn("[VoiceAnnounce] whisper-cli or model not ready.");
            return;
        }
        if (captureThread != null && captureThread.isAlive()) return;

        capturing.set(true);
        audioBuffer.reset();
        RenderOverlayHandler.setState(RenderOverlayHandler.State.LISTENING);

        captureThread = new Thread(VoiceRecognitionThread::recordLoop, "VoiceAnnounce-Record");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public static void stopCapture() {
        capturing.set(false);
    }

    private static void recordLoop() {
        TargetDataLine mic = null;
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, AUDIO_FORMAT);
            if (!AudioSystem.isLineSupported(info)) {
                VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Mic not supported at 16kHz/16-bit/mono.");
                RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
                return;
            }
            mic = (TargetDataLine) AudioSystem.getLine(info);
            mic.open(AUDIO_FORMAT);
            mic.start();

            byte[] chunk = new byte[4096];
            while (capturing.get()) {
                int n = mic.read(chunk, 0, chunk.length);
                if (n > 0) audioBuffer.write(chunk, 0, n);
            }
        } catch (LineUnavailableException e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Mic error: {}", e.getMessage());
            RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
            return;
        } finally {
            if (mic != null) { mic.stop(); mic.close(); }
        }

        processAudio(audioBuffer.toByteArray());
    }

    private static void processAudio(byte[] audio) {
        if (audio.length == 0) {
            RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
            return;
        }

        RenderOverlayHandler.setState(RenderOverlayHandler.State.TRANSCRIBING);
        String transcript = runWhisper(audio);
        if (transcript == null || transcript.trim().isEmpty()) {
            RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
            return;
        }
        transcript = transcript.trim();
        showTranscript(transcript);

        // Local-first dispatch: try VoiceBot-style phrase triggers before
        // burning a Gemini turn. If a trigger matches, queue its macro/steps
        // directly and skip the model entirely.
        try {
            TriggerMatcher.Match local = TriggerMatcher.match(transcript);
            if (local != null) {
                JsonObject state = fetchState();
                List<ToolCall> steps;
                if (local.trigger.macroName != null) {
                    steps = MacroHandler.expandMacro(local.trigger.macroName, state, local.params);
                    if (steps == null) {
                        sendChat(TextFormatting.RED + "[voice] trigger references unknown macro: "
                            + local.trigger.macroName);
                        RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
                        return;
                    }
                } else {
                    steps = MacroHandler.convertJsonSteps(local.trigger.inlineSteps, local.params);
                }
                ChainValidator.Result v = ChainValidator.validate(steps);
                if (!v.ok) {
                    sendChat(TextFormatting.RED + "[voice] " + v.error);
                } else if (steps.isEmpty()) {
                    sendChat(TextFormatting.AQUA + "[voice] (trigger matched, no steps)");
                } else {
                    VoiceAnnounce.getQueue().submit(steps);
                    sendChat(TextFormatting.DARK_GRAY + "[voice] trigger '" + local.trigger.firstPhrase
                        + "' → " + steps.size() + " step(s)");
                }
                RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
                return;
            }
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] trigger dispatch failed", e);
        }

        GeminiClient client = VoiceAnnounce.getClient();
        if (client == null) {
            sendChat(TextFormatting.RED + "[voice] No Gemini API key configured.");
            RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
            return;
        }

        try {
            JsonObject state = fetchState();
            List<ToolCall> calls = client.sendTurn(transcript, state);

            // Process macro ops up front:
            //   define_macro — register and persist; result reported back to model.
            //   run_macro    — replaced by expansion; the stub itself is dropped.
            // Both are filtered out before validation/queue.
            List<ToolCall> flattened = new ArrayList<>();
            List<ToolResult> macroOps = new ArrayList<>();
            boolean unknownMacro = false;
            String unknownMacroName = null;
            for (ToolCall c : calls) {
                if (c.name.equals("define_macro")) {
                    ToolResult r = MacroHandler.defineMacro(c);
                    macroOps.add(r);
                    sendChat((r.ok ? TextFormatting.GRAY : TextFormatting.RED)
                        + "[voice] " + r.message);
                } else if (c.name.equals("run_macro")) {
                    String macroName = c.argString("name", "");
                    List<ToolCall> exp = MacroHandler.expandMacro(macroName, state);
                    if (exp == null) {
                        unknownMacro = true;
                        unknownMacroName = macroName;
                        break;
                    }
                    flattened.addAll(exp);
                } else {
                    flattened.add(c);
                }
            }
            if (unknownMacro) {
                sendChat(TextFormatting.RED + "[voice] unknown macro: " + unknownMacroName);
                macroOps.add(ToolResult.fail("run_macro", "unknown macro: " + unknownMacroName));
                client.reportResults(macroOps);
                return;
            }

            ChainValidator.Result validation = ChainValidator.validate(flattened);
            if (!validation.ok) {
                sendChat(TextFormatting.RED + "[voice] " + validation.error);
                macroOps.add(ToolResult.fail("_validator", validation.error));
                client.reportResults(macroOps);
            } else if (flattened.isEmpty()) {
                if (macroOps.isEmpty()) {
                    sendChat(TextFormatting.AQUA + "[voice] (no actions)");
                } else {
                    client.reportResults(macroOps);
                }
            } else {
                if (!macroOps.isEmpty()) client.reportResults(macroOps);
                VoiceAnnounce.getQueue().submit(flattened);
                sendChat(TextFormatting.GRAY + "[voice] executing " + flattened.size() + " step(s)");
            }
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Gemini call failed", e);
            sendChat(TextFormatting.RED + "[voice] Gemini error: " + e.getMessage());
        } finally {
            RenderOverlayHandler.setState(RenderOverlayHandler.State.IDLE);
        }
    }

    private static JsonObject fetchState() throws InterruptedException, ExecutionException {
        return Minecraft.getMinecraft().addScheduledTask(PlayerState::snapshot).get();
    }

    private static String runWhisper(byte[] audio) {
        File wav = null, outPrefix = null;
        try {
            wav = File.createTempFile("voiceannounce_", ".wav");
            AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(audio), AUDIO_FORMAT,
                audio.length / AUDIO_FORMAT.getFrameSize());
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav);

            outPrefix = File.createTempFile("voiceannounce_out_", "");
            outPrefix.delete();

            ProcessBuilder pb = new ProcessBuilder(
                VoiceAnnounce.whisperBin,
                "-m", VoiceAnnounce.modelPath,
                "-f", wav.getAbsolutePath(),
                "-otxt",
                "-of", outPrefix.getAbsolutePath(),
                "-nt", "-l", "en", "-t", "4"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    InputStream is = proc.getInputStream();
                    while (is.read(buf) != -1) {}
                } catch (Exception ignored) {}
            }).start();

            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                VoiceAnnounce.LOGGER.warn("[VoiceAnnounce] whisper-cli timed out.");
                return null;
            }

            File txt = new File(outPrefix.getAbsolutePath() + ".txt");
            if (!txt.exists()) return null;
            String text = new String(Files.readAllBytes(txt.toPath())).trim();
            txt.delete();
            return text;
        } catch (Exception e) {
            VoiceAnnounce.LOGGER.error("[VoiceAnnounce] Whisper error", e);
            return null;
        } finally {
            if (wav != null) wav.delete();
        }
    }

    private static void showTranscript(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.player == null) return;
            mc.player.sendMessage(new TextComponentString(
                TextFormatting.YELLOW + "[voice] " + TextFormatting.WHITE + text));
        });
    }

    private static void sendChat(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.player == null) return;
            mc.player.sendMessage(new TextComponentString(text));
        });
    }
}
