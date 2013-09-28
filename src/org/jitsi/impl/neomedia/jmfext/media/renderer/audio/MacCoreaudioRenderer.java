/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.jmfext.media.renderer.audio;

import java.beans.*;
import java.lang.reflect.*;
import java.util.*;

import javax.media.*;
import javax.media.format.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.control.*;
import org.jitsi.impl.neomedia.device.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.nio.ByteBuffer;

/**
 * Implements an audio <tt>Renderer</tt> which uses MacOSX Coreaudio.
 *
 * @author Vincent Lucas
 */
public class MacCoreaudioRenderer
    extends AbstractAudioRenderer<MacCoreaudioSystem>
{
    /**
     * The <tt>Logger</tt> used by the <tt>MacCoreaudioRenderer</tt> class and
     * its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(MacCoreaudioRenderer.class);

    /**
     * The device used for this renderer.
     */
    private String deviceUID = null;

    /**
     * The stream structure used by the native maccoreaudio library.
     */
    private long stream = 0;

    /**
     * A mutual eclusion used to avoid conflict when starting / stoping the
     * stream for this renderer;
     */
    private Object startStopMutex = new Object();

    /**
     * The buffer which stores th incoming data before sending them to
     * CoreAudio.
     */
    private byte[] buffer = null;

    /**
     * The number of data available to feed CoreAudio output.
     */
    private int nbBufferData = 0;

    private boolean isStopping = false;

    /**
     * The constant which represents an empty array with
     * <tt>Format</tt> element type. Explicitly defined in order to
     * reduce unnecessary allocations.
     */
    private static final Format[] EMPTY_SUPPORTED_INPUT_FORMATS
        = new Format[0];

    /**
     * The human-readable name of the <tt>MacCoreaudioRenderer</tt> JMF plug-in.
     */
    private static final String PLUGIN_NAME = "MacCoreaudio Renderer";

    /**
     * The list of JMF <tt>Format</tt>s of audio data which
     * <tt>MacCoreaudioRenderer</tt> instances are capable of rendering.
     */
    private static final Format[] SUPPORTED_INPUT_FORMATS;

    /**
     * The list of the sample rates supported by <tt>MacCoreaudioRenderer</tt>
     * as input.
     */
    private static final double[] SUPPORTED_INPUT_SAMPLE_RATES
        = new double[] { 8000, 11025, 16000, 22050, 32000, 44100, 48000 };

    static
    {
        int count = SUPPORTED_INPUT_SAMPLE_RATES.length;

        SUPPORTED_INPUT_FORMATS = new Format[count];
        for (int i = 0; i < count; i++)
        {
            SUPPORTED_INPUT_FORMATS[i]
                = new AudioFormat(
                        AudioFormat.LINEAR,
                        SUPPORTED_INPUT_SAMPLE_RATES[i],
                        16 /* sampleSizeInBits */,
                        Format.NOT_SPECIFIED /* channels */,
                        AudioFormat.LITTLE_ENDIAN,
                        AudioFormat.SIGNED,
                        Format.NOT_SPECIFIED /* frameSizeInBits */,
                        Format.NOT_SPECIFIED /* frameRate */,
                        Format.byteArray);
        }
    }

    /**
     * The <tt>UpdateAvailableDeviceListListener</tt> which is to be notified
     * before and after MacCoreaudio's native function
     * <tt>UpdateAvailableDeviceList()</tt> is invoked. It will close
     * {@link #stream} before the invocation in order to mitigate memory
     * corruption afterwards and it will attempt to restore the state of this
     * <tt>Renderer</tt> after the invocation.
     */
    private final MacCoreaudioSystem.UpdateAvailableDeviceListListener
        updateAvailableDeviceListListener
            = new MacCoreaudioSystem.UpdateAvailableDeviceListListener()
    {
        private boolean start = false;

        public void didUpdateAvailableDeviceList()
            throws Exception
        {
            synchronized(startStopMutex)
            {
                updateDeviceUID();
                if(start)
                {
                    open();
                    start();
                }
            }
        }

        public void willUpdateAvailableDeviceList()
            throws Exception
        {
            synchronized(startStopMutex)
            {
                start = false;
                if(stream != 0)
                {
                    start = true;
                    stop();
                }
            }
        }
    };

    /**
     * Array of supported input formats.
     */
    private Format[] supportedInputFormats;

    /**
     * Initializes a new <tt>MacCoreaudioRenderer</tt> instance.
     */
    public MacCoreaudioRenderer()
    {
        this(true);
    }

    /**
     * Initializes a new <tt>MacCoreaudioRenderer</tt> instance which is to
     * either perform playback or sound a notification.
     *
     * @param playback <tt>true</tt> if the new instance is to perform playback
     * or <tt>false</tt> if the new instance is to sound a notification
     */
    public MacCoreaudioRenderer(boolean enableVolumeControl)
    {
        super(
                AudioSystem.LOCATOR_PROTOCOL_MACCOREAUDIO,
                enableVolumeControl
                    ? AudioSystem.DataFlow.PLAYBACK
                    : AudioSystem.DataFlow.NOTIFY);

        // XXX We will add a PaUpdateAvailableDeviceListListener and will not
        // remove it because we will rely on MacCoreaudioSystem's use of
        // WeakReference.
        MacCoreaudioSystem.addUpdateAvailableDeviceListListener(
                updateAvailableDeviceListListener);
    }

    /**
     * Closes this <tt>PlugIn</tt>.
     */
    @Override
    public void close()
    {
        stop();
        super.close();
    }

    /**
     * Gets the descriptive/human-readable name of this JMF plug-in.
     *
     * @return the descriptive/human-readable name of this JMF plug-in
     */
    public String getName()
    {
        return PLUGIN_NAME;
    }

    /**
     * Gets the list of JMF <tt>Format</tt>s of audio data which this
     * <tt>Renderer</tt> is capable of rendering.
     *
     * @return an array of JMF <tt>Format</tt>s of audio data which this
     * <tt>Renderer</tt> is capable of rendering
     */
    @Override
    public Format[] getSupportedInputFormats()
    {
        if (supportedInputFormats == null)
        {
            MediaLocator locator = getLocator();
            this.updateDeviceUID();

            if(deviceUID == null)
            {
                supportedInputFormats = SUPPORTED_INPUT_FORMATS;
            }
            else
            {
                int minOutputChannels = 1;
                // The maximum output channels may be a lot and checking all of
                // them will take a lot of time. Besides, we currently support
                // at most 2.
                int maxOutputChannels
                    = Math.min(
                            MacCoreAudioDevice.countOutputChannels(deviceUID),
                            2);
                List<Format> supportedInputFormats
                    = new ArrayList<Format>(SUPPORTED_INPUT_FORMATS.length);

                for (Format supportedInputFormat : SUPPORTED_INPUT_FORMATS)
                {
                    getSupportedInputFormats(
                            supportedInputFormat,
                            minOutputChannels,
                            maxOutputChannels,
                            supportedInputFormats);
                }

                this.supportedInputFormats
                    = supportedInputFormats.isEmpty()
                        ? EMPTY_SUPPORTED_INPUT_FORMATS
                        : supportedInputFormats.toArray(
                                EMPTY_SUPPORTED_INPUT_FORMATS);
            }
        }
        return
            (supportedInputFormats.length == 0)
                ? EMPTY_SUPPORTED_INPUT_FORMATS
                : supportedInputFormats.clone();
    }

    private void getSupportedInputFormats(
            Format format,
            int minOutputChannels,
            int maxOutputChannels,
            List<Format> supportedInputFormats)
    {
        AudioFormat audioFormat = (AudioFormat) format;
        int sampleSizeInBits = audioFormat.getSampleSizeInBits();
        double sampleRate = audioFormat.getSampleRate();
        float minRate
            = MacCoreAudioDevice.getMinimalNominalSampleRate(deviceUID);
        float maxRate
            = MacCoreAudioDevice.getMaximalNominalSampleRate(deviceUID);

        for(int channels = minOutputChannels;
                channels <= maxOutputChannels;
                channels++)
        {
            if(sampleRate >= minRate && sampleRate <= maxRate)
            {
                supportedInputFormats.add(
                        new AudioFormat(
                            audioFormat.getEncoding(),
                            sampleRate,
                            sampleSizeInBits,
                            channels,
                            audioFormat.getEndian(),
                            audioFormat.getSigned(),
                            Format.NOT_SPECIFIED, // frameSizeInBits
                            Format.NOT_SPECIFIED, // frameRate
                            audioFormat.getDataType()));
            }
        }
    }

    /**
     * Opens the MacCoreaudio device and output stream represented by this
     * instance which are to be used to render audio.
     *
     * @throws ResourceUnavailableException if the MacCoreaudio device or output
     * stream cannot be created or opened
     */
    @Override
    public void open()
        throws ResourceUnavailableException
    {
        synchronized(startStopMutex)
        {
            if(stream == 0)
            {
                MacCoreaudioSystem.willOpenStream();
                try
                {
                    if(!this.updateDeviceUID())
                    {
                        throw new ResourceUnavailableException(
                                "No locator/MediaLocator is set.");
                    }
                }
                finally
                {
                    MacCoreaudioSystem.didOpenStream();
                }

            }
            super.open();
        }
    }

    /**
     * Notifies this instance that the value of the
     * {@link AudioSystem#PROP_PLAYBACK_DEVICE} property of its associated
     * <tt>AudioSystem</tt> has changed.
     *
     * @param ev a <tt>PropertyChangeEvent</tt> which specifies details about
     * the change such as the name of the property and its old and new values
     */
    @Override
    protected synchronized void playbackDevicePropertyChange(
            PropertyChangeEvent ev)
    {
        synchronized(startStopMutex)
        {
            stop();
            updateDeviceUID();
            start();
        }
    }

    /**
     * Renders the audio data contained in a specific <tt>Buffer</tt> onto the
     * MacCoreaudio device represented by this <tt>Renderer</tt>.
     *
     * @param buffer the <tt>Buffer</tt> which contains the audio data to be
     * rendered
     * @return <tt>BUFFER_PROCESSED_OK</tt> if the specified <tt>buffer</tt> has
     * been successfully processed
     */
    public int process(Buffer buffer)
    {
        synchronized(startStopMutex)
        {
            if(stream != 0 && !isStopping)
            {
                // Take into account the user's preferences with respect to the
                // output volume.
                GainControl gainControl = getGainControl();
                if (gainControl != null)
                {
                    BasicVolumeControl.applyGain(
                            gainControl,
                            (byte[]) buffer.getData(),
                            buffer.getOffset(),
                            buffer.getLength());
                }

                int length = buffer.getLength();

                // Update the buffer size if too small.
                int timeout = 2000;
                int maxNbBuffers
                    = timeout / MacCoreAudioDevice.DEFAULT_MILLIS_PER_BUFFER;
                updateBufferLength(
                        Math.min(
                            nbBufferData + length,
                            length * maxNbBuffers));

                if(nbBufferData + length > this.buffer.length)
                {
                    length = this.buffer.length - nbBufferData;
                }

                // Copy the received data.
                System.arraycopy(
                        (byte[]) buffer.getData(),
                        buffer.getOffset(),
                        this.buffer,
                        nbBufferData,
                        length);
                nbBufferData += length;
            }
        }
        return BUFFER_PROCESSED_OK;
    }

    /**
     * Sets the <tt>MediaLocator</tt> which specifies the device index of the
     * MacCoreaudio device to be used by this instance for rendering.
     *
     * @param locator a <tt>MediaLocator</tt> which specifies the device index
     * of the MacCoreaudio device to be used by this instance for rendering
     */
    @Override
    public void setLocator(MediaLocator locator)
    {
        super.setLocator(locator);

        this.updateDeviceUID();

        supportedInputFormats = null;
    }

    /**
     * Starts the rendering process. Any audio data available in the internal
     * resources associated with this <tt>MacCoreaudioRenderer</tt> will begin
     * being rendered.
     */
    public void start()
    {
        // Start the stream
        synchronized(startStopMutex)
        {
            if(stream == 0 && deviceUID != null)
            {
                int nbChannels = inputFormat.getChannels();
                if (nbChannels == Format.NOT_SPECIFIED)
                    nbChannels = 1;

                MacCoreaudioSystem.willOpenStream();
                stream = MacCoreAudioDevice.startStream(
                        deviceUID,
                        this,
                        (float) inputFormat.getSampleRate(),
                        nbChannels,
                        inputFormat.getSampleSizeInBits(),
                        false,
                        inputFormat.getEndian() == AudioFormat.BIG_ENDIAN,
                        false);
                MacCoreaudioSystem.didOpenStream();
            }
        }
    }

    /**
     * Stops the rendering process.
     */
    public void stop()
    {
        synchronized(startStopMutex)
        {
            if(stream != 0 && deviceUID != null && !isStopping)
            {
                this.isStopping = true;
                long timeout = 500;
                long startTime = System.currentTimeMillis();
                long currentTime = startTime;
                long startNbData = nbBufferData;
                while(nbBufferData > 0
                        && (currentTime - startTime) < timeout)
                {
                    try
                    {
                        startStopMutex.wait(timeout);
                    }
                    catch(InterruptedException ex)
                    {
                    }

                    currentTime = System.currentTimeMillis();
                    if(startNbData > nbBufferData)
                    {
                        startTime = currentTime;
                        startNbData = nbBufferData;
                    }
                }

                MacCoreAudioDevice.stopStream(deviceUID, stream);
                stream = 0;
                buffer = null;
                nbBufferData = 0;
                this.isStopping = false;
            }
        }
    }

    /**
     * Writes the data received to the buffer give in arguments, which is
     * provided by the CoreAudio library.
     *
     * @param buffer The buffer to fill in provided by the CoreAudio library.
     * @param bufferLength The length of the buffer provided.
     */
    public void writeOutput(byte[] buffer, int bufferLength)
    {
        synchronized(startStopMutex)
        {
            updateBufferLength(bufferLength);

            int i = 0;
            int length = nbBufferData;
            if(bufferLength < length)
            {
                length = bufferLength;
            }

            System.arraycopy(this.buffer, 0, buffer, 0, length);

            // Fiils the end of the buffer with silence.
            if(length < bufferLength)
            {
                Arrays.fill(buffer, length, bufferLength, (byte) 0);
            }


            nbBufferData -= length;
            if(nbBufferData > 0)
            {
                System.arraycopy(
                        this.buffer, length, this.buffer, 0, nbBufferData);
            }
            // If the stop process is waiting, notifies it  that every sample
            // has been consummed.
            // (nbBufferData == 0)
            else
            {
                startStopMutex.notify();
            }
        }
    }

    /**
     * Updates the deviceUID based on the current locator.
     *
     * @return True if the deviceUID has been updated. False otherwise.
     */
    private boolean updateDeviceUID()
    {
        MediaLocator locator = getLocator();
        if(locator != null)
        {
            String remainder = locator.getRemainder();
            if(remainder != null && remainder.length() > 1)
            {
                synchronized(startStopMutex)
                {
                    this.deviceUID = remainder.substring(1);
                }
                return true;
            }
        }
        return false;
    }

    private void updateBufferLength(int newLength)
    {
        synchronized(startStopMutex)
        {
            if(this.buffer == null)
            {
                this.buffer = new byte[newLength];
                nbBufferData = 0;
            }
            else if(newLength > this.buffer.length)
            {
                byte[] newBuffer = new byte[newLength];
                System.arraycopy(
                        this.buffer,
                        0,
                        newBuffer,
                        0,
                        nbBufferData);
                this.buffer = newBuffer;
            }
        }
    }
}
