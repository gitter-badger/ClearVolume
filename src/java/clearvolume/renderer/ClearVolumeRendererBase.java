package clearvolume.renderer;

import static java.lang.Math.PI;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sqrt;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.SwingUtilities;

import clearvolume.ClearVolumeCloseable;
import clearvolume.controller.AutoRotationController;
import clearvolume.controller.RotationControllerInterface;
import clearvolume.exceptions.VolumeTooBigException;
import clearvolume.renderer.listeners.EyeRayListener;
import clearvolume.renderer.listeners.ParameterChangeListener;
import clearvolume.renderer.listeners.VolumeCaptureListener;
import clearvolume.renderer.processors.Processor;
import clearvolume.transferf.TransferFunction;
import clearvolume.transferf.TransferFunctions;
import clearvolume.volume.Volume;
import clearvolume.volume.VolumeManager;

import com.jogamp.opengl.math.Quaternion;

import coremem.ContiguousMemoryInterface;
import coremem.fragmented.FragmentedMemory;
import coremem.fragmented.FragmentedMemoryInterface;
import coremem.offheap.OffHeapMemory;
import coremem.types.NativeTypeEnum;
import coremem.util.Size;

/**
 * Class ClearVolumeRendererBase
 *
 * Instances of this class ...
 *
 * @author Loic Royer (2014), Florian Jug (2015)
 *
 */

public abstract class ClearVolumeRendererBase	implements
																							ClearVolumeRendererInterface,
																							ClearVolumeCloseable
{

	/**
	 * Default FOV
	 */
	public static final float cDefaultFOV = .785f;
	public static final float cOrthoLikeFOV = .01f;
	public static final float cMinimalFOV = cOrthoLikeFOV;
	public static final float cMaximalFOV = (float) (0.75 * PI);

	// Timeout:
	private static final long cDefaultSetVolumeDataBufferTimeout = 5;

	/**
	 * Number of render layers.
	 */
	private int mNumberOfRenderLayers;
	private volatile int mCurrentRenderLayerIndex = 0;

	/**
	 * Number of bytes per voxel used by this renderer
	 */
	private volatile NativeTypeEnum mNativeType = NativeTypeEnum.Byte;

	/**
	 * Rotation controller in addition to the mouse
	 */
	private final ArrayList<RotationControllerInterface> mRotationControllerList = new ArrayList<RotationControllerInterface>();

	/**
	 * Auto rotation controller
	 */
	private final AutoRotationController mAutoRotationController;

	/**
	 * Transfer functions used
	 */
	private final TransferFunction[] mTransferFunctions;

	private final boolean[] mLayerVisiblityFlagArray;

	// geometric, brigthness an contrast settings.
	private final Quaternion mRotationQuaternion = new Quaternion();
	private volatile float mTranslationX = 0;
	private volatile float mTranslationY = 0;
	private volatile float mTranslationZ = 0;

	private volatile float mFOV = cDefaultFOV;

	// Render algorithm per layer:
	private final RenderAlgorithm[] mRenderAlgorithm;

	// render parameters per layer;
	private final float[] mBrightness;
	private final float[] mTransferFunctionRangeMin;
	private final float[] mTransferFunctionRangeMax;
	private final float[] mGamma;
	private final float[] mQuality;
	private final float[] mDithering;

	private volatile boolean mVolumeRenderingParametersChanged = true;

	// volume dimensions settings
	private volatile long mVolumeSizeX;
	private volatile long mVolumeSizeY;
	private volatile long mVolumeSizeZ;

	private volatile double mVoxelSizeX;
	private volatile double mVoxelSizeY;
	private volatile double mVoxelSizeZ;

	private volatile boolean mVolumeDimensionsChanged;

	// data copy locking and waiting
	private final Object[] mSetVolumeDataBufferLocks;
	private final FragmentedMemoryInterface[] mVolumeDataByteBuffers;
	private final CountDownLatch[] mDataBufferCopyIsFinishedArray;

	// Control frame:
	private ControlPanelJFrame mControlFrame;

	// Map of processors:
	protected Map<String, Processor<?>> mProcessorsMap = new ConcurrentHashMap<>();

	// List of Capture Listeners
	protected ArrayList<VolumeCaptureListener> mVolumeCaptureListenerList = new ArrayList<VolumeCaptureListener>();
	protected volatile boolean mVolumeCaptureFlag = false;

	// Adaptive LOD controller:
	protected AdaptiveLODController mAdaptiveLODController;

	// Eye ray listeners:
	protected CopyOnWriteArrayList<EyeRayListener> mEyeRayListenerList = new CopyOnWriteArrayList<EyeRayListener>();

	// Eye ray listeners:
	protected CopyOnWriteArrayList<ParameterChangeListener> mParameterChangeListenerList = new CopyOnWriteArrayList<ParameterChangeListener>();

	// Display lock:
	protected final ReentrantLock mDisplayReentrantLock = new ReentrantLock(true);

	public ClearVolumeRendererBase(final int pNumberOfRenderLayers)
	{
		super();

		mNumberOfRenderLayers = pNumberOfRenderLayers;
		mSetVolumeDataBufferLocks = new Object[pNumberOfRenderLayers];
		mVolumeDataByteBuffers = new FragmentedMemoryInterface[pNumberOfRenderLayers];
		mDataBufferCopyIsFinishedArray = new CountDownLatch[pNumberOfRenderLayers];
		mTransferFunctions = new TransferFunction[pNumberOfRenderLayers];
		mLayerVisiblityFlagArray = new boolean[pNumberOfRenderLayers];
		mRenderAlgorithm = new RenderAlgorithm[pNumberOfRenderLayers];
		mBrightness = new float[pNumberOfRenderLayers];
		mTransferFunctionRangeMin = new float[pNumberOfRenderLayers];
		mTransferFunctionRangeMax = new float[pNumberOfRenderLayers];
		mGamma = new float[pNumberOfRenderLayers];
		mQuality = new float[pNumberOfRenderLayers];
		mDithering = new float[pNumberOfRenderLayers];

		for (int i = 0; i < pNumberOfRenderLayers; i++)
		{
			mSetVolumeDataBufferLocks[i] = new Object();
			mTransferFunctions[i] = TransferFunctions.getGradientForColor(i);
			mLayerVisiblityFlagArray[i] = true;
			mRenderAlgorithm[i] = RenderAlgorithm.MaxProjection;
			mBrightness[i] = 1;
			mTransferFunctionRangeMin[i] = 0f;
			mTransferFunctionRangeMax[i] = 1f;
			mGamma[i] = 1f;
			mQuality[i] = 1f;
			mDithering[i] = 1f;
		}

		if (pNumberOfRenderLayers == 1)
			mTransferFunctions[0] = TransferFunctions.getDefault();

		mAdaptiveLODController = new AdaptiveLODController();

		mAutoRotationController = new AutoRotationController();
		mRotationControllerList.add(mAutoRotationController);

	}

	/**
	 * Sets the native type of this renderer. This is _usually_ set at
	 * construction time and should not be modified later
	 *
	 * @param pNativeTypeEnum
	 *          native type
	 */
	@Override
	public void setNativeType(final NativeTypeEnum pNativeTypeEnum)

	{
		mNativeType = pNativeTypeEnum;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getNativeType()
	 */
	@Override
	public NativeTypeEnum getNativeType()
	{
		return mNativeType;
	}

	/**
	 * Returns the number of bytes per voxel for this renderer.
	 * 
	 * @return bytes per voxel
	 */
	@Override
	public long getBytesPerVoxel()
	{
		return Size.of(mNativeType);
	}

	/**
	 * Returns the state of the flag indicating whether current/new rendering
	 * parameters have been used for last rendering.
	 *
	 * @return true if rendering parameters up-to-date.
	 */
	public boolean haveVolumeRenderingParametersChanged()
	{
		return mVolumeRenderingParametersChanged;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#notifyChangeOfVolumeRenderingParameters()
	 */
	@Override
	public void notifyChangeOfVolumeRenderingParameters()
	{
		for (final ParameterChangeListener lParameterChangeListener : mParameterChangeListenerList)
		{
			lParameterChangeListener.notifyParameterChange(this);
		}

		mVolumeRenderingParametersChanged = true;
		getAdaptiveLODController().notifyUserInteractionInProgress();
	}

	@Override
	public void addParameterChangeListener(ParameterChangeListener pParameterChangeListener)
	{
		mParameterChangeListenerList.add(pParameterChangeListener);
	}

	@Override
	public void removeParameterChangeListener(ParameterChangeListener pParameterChangeListener)
	{
		mParameterChangeListenerList.remove(pParameterChangeListener);
	}

	/**
	 * Clears the state of the update-volume-parameters flag
	 */
	public void clearChangeOfVolumeParametersFlag()
	{
		mVolumeRenderingParametersChanged = false;
	}

	/**
	 * Returns the volume size along x axis.
	 *
	 * @return volume size along x
	 */
	public long getVolumeSizeX()
	{
		return mVolumeSizeX;
	}

	/**
	 * Returns the volume size along y axis.
	 *
	 * @return volume size along y
	 */
	public long getVolumeSizeY()
	{
		return mVolumeSizeY;
	}

	/**
	 * Returns the volume size along z axis.
	 *
	 * @return volume size along z
	 */
	public long getVolumeSizeZ()
	{
		return mVolumeSizeZ;
	}

	public double getVoxelSizeX()
	{
		return mVoxelSizeX;
	}

	public double getVoxelSizeY()
	{
		return mVoxelSizeY;
	}

	public double getVoxelSizeZ()
	{
		return mVoxelSizeZ;
	}

	/**
	 * Returns whether the volume dimensions have been changed since last data
	 * upload.
	 *
	 * @return true if volume dimensions changed.
	 */
	public boolean haveVolumeDimensionsChanged()
	{
		return mVolumeDimensionsChanged;
	}

	/**
	 *
	 */
	public void clearVolumeDimensionsChanged()
	{
		mVolumeDimensionsChanged = false;
	}

	/**
	 * Gets active flag for the current render layer.
	 *
	 * @return true if layer visible
	 */
	@Override
	public boolean isLayerVisible()
	{
		return isLayerVisible(getCurrentRenderLayerIndex());
	}

	/**
	 * Gets active flag for the given render layer.
	 *
	 * @return true if layer visible
	 */
	@Override
	public boolean isLayerVisible(final int pRenderLayerIndex)
	{
		return mLayerVisiblityFlagArray[pRenderLayerIndex];
	}

	/**
	 * Sets active flag for the current render layer.
	 *
	 * @param pVisible
	 *          true to set layer visible, false to set it invisible
	 */
	@Override
	public void setLayerVisible(boolean pVisible)
	{
		setLayerVisible(getCurrentRenderLayerIndex(), pVisible);
	}

	/**
	 * Sets active flag for the given render layer.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pVisible
	 *          true to set layer visible, false to set it invisible
	 */
	@Override
	public void setLayerVisible(final int pRenderLayerIndex,
															final boolean pVisible)
	{
		getDisplayLock().lock();
		try
		{
			mLayerVisiblityFlagArray[pRenderLayerIndex] = pVisible;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#resetBrightnessAndGammaAndTransferFunctionRanges()
	 */
	@Override
	public void resetBrightnessAndGammaAndTransferFunctionRanges()
	{
		getDisplayLock().lock();
		try
		{
			for (int i = 0; i < getNumberOfRenderLayers(); i++)
			{
				mBrightness[i] = 1.0f;
				mGamma[i] = 1.0f;
				mTransferFunctionRangeMin[i] = 0.0f;
				mTransferFunctionRangeMax[i] = 1.0f;
			}
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	}

	/**
	 * 
	 * Returns the brightness level of the current render layer.
	 *
	 * @return brightness level.
	 */
	@Override
	public double getBrightness()
	{
		return getBrightness(getCurrentRenderLayerIndex());
	}

	/**
	 * Returns the brightness level of a given render layer index.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return brightness level.
	 */
	@Override
	public double getBrightness(final int pRenderLayerIndex)
	{
		return mBrightness[pRenderLayerIndex];
	}

	/**
	 * Sets brightness.
	 *
	 * @param pBrightness
	 *          brightness level
	 */
	@Override
	public void setBrightness(final double pBrightness)
	{
		setBrightness(getCurrentRenderLayerIndex(), pBrightness);
	}

	/**
	 * Sets brightness for a given render layer index.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pBrightness
	 *          brightness level
	 */
	@Override
	public void setBrightness(final int pRenderLayerIndex,
														final double pBrightness)
	{
		getDisplayLock().lock();
		try
		{
			mBrightness[pRenderLayerIndex] = (float) clamp(	pBrightness,
																											0,
																											getNativeType() == NativeTypeEnum.UnsignedByte ? 16
																																																		: 256);

			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	}

	/**
	 * Returns the Gamma size.
	 *
	 * @return gamma size
	 */
	@Override
	public double getGamma()
	{
		return getGamma(getCurrentRenderLayerIndex());
	}

	/**
	 * Returns the Gamma value.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return gamma value for layer
	 */
	@Override
	public double getGamma(final int pRenderLayerIndex)
	{
		return mGamma[pRenderLayerIndex];
	}

	/**
	 * Sets the gamma for the current render layer index.
	 *
	 * @param pGamma
	 *          gamma value
	 */
	@Override
	public void setGamma(final double pGamma)
	{
		setGamma(getCurrentRenderLayerIndex(), pGamma);
	}

	/**
	 * Sets the gamma for a given render layer index.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pGamma
	 *          gamma value
	 */
	@Override
	public void setGamma(	final int pRenderLayerIndex,
												final double pGamma)

	{
		getDisplayLock().lock();
		try
		{
			mGamma[pRenderLayerIndex] = (float) pGamma;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Sets dithering value [0,1].
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pDithering
	 *          new dithering level for render layer
	 */
	@Override
	public void setDithering(int pRenderLayerIndex, double pDithering)
	{
		getDisplayLock().lock();
		try
		{
			mDithering[pRenderLayerIndex] = (float) pDithering;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	};

	/**
	 * Returns the amount of dithering [0,1] for a given render layer.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return dithering
	 */
	@Override
	public float getDithering(int pRenderLayerIndex)
	{
		return mDithering[pRenderLayerIndex];
	};

	/**
	 * Sets the render quality [0,1] for a given render layer.
	 * 
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pQuality
	 *          new quality level for render layer
	 */
	@Override
	public void setQuality(int pRenderLayerIndex, double pQuality)
	{
		getDisplayLock().lock();
		try
		{
			pQuality = max(min(pQuality, 1), 0);
			mQuality[pRenderLayerIndex] = (float) pQuality;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	};

	/**
	 * Returns the quality level [0,1] for a given render layer.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return quality level
	 */
	@Override
	public float getQuality(int pRenderLayerIndex)
	{
		return mQuality[pRenderLayerIndex];
	};

	/**
	 * Returns the maximal number of steps during ray casting for a given layer.
	 * This size depends on the volume dimension and quality.
	 * 
	 * @param pRenderLayerIndex
	 *          renderlayer index
	 * @return maximal number of steps
	 */
	public int getMaxSteps(final int pRenderLayerIndex)
	{
		return (int) (sqrt(getVolumeSizeX() * getVolumeSizeX()
												+ getVolumeSizeY()
												* getVolumeSizeY()
												+ getVolumeSizeZ()
												* getVolumeSizeZ()) * getQuality(pRenderLayerIndex));
	}

	/**
	 * Returns the minimum of the transfer function range for the current render
	 * layer.
	 *
	 * @return minimum
	 */
	@Override
	public double getTransferRangeMin()
	{
		return getTransferRangeMin(getCurrentRenderLayerIndex());
	}

	/**
	 * Returns the minimum of the transfer function range for a given render
	 * layer.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return minimum of transfer function range
	 */
	@Override
	public double getTransferRangeMin(final int pRenderLayerIndex)
	{
		return mTransferFunctionRangeMin[pRenderLayerIndex];
	}

	/**
	 * 
	 * Returns the maximum of the transfer function range for the current render
	 * layer index.
	 *
	 * @return minimum
	 */
	@Override
	public double getTransferRangeMax()
	{
		return getTransferRangeMax(getCurrentRenderLayerIndex());
	}

	/**
	 * Returns the maximum of the transfer function range for a given render layer
	 * index.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return maximum of transfer function range
	 */
	@Override
	public double getTransferRangeMax(final int pRenderLayerIndex)
	{
		return mTransferFunctionRangeMax[pRenderLayerIndex];
	}

	/**
	 * Sets the transfer function range min and max for the current render layer
	 * index.
	 *
	 * @param pTransferRangeMin
	 *          transfer range min
	 * @param pTransferRangeMax
	 *          transfer range max
	 */
	@Override
	public void setTransferFunctionRange(	final double pTransferRangeMin,
																				final double pTransferRangeMax)
	{
		setTransferFunctionRange(	getCurrentRenderLayerIndex(),
															pTransferRangeMin,
															pTransferRangeMax);
	}

	/**
	 * Sets the transfer function range min and max for a given render layer
	 * index.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pTransferRangeMin
	 *          transfer range min
	 * @param pTransferRangeMax
	 *          transfer range max
	 */
	@Override
	public void setTransferFunctionRange(	final int pRenderLayerIndex,
																				final double pTransferRangeMin,
																				final double pTransferRangeMax)
	{
		getDisplayLock().lock();
		try
		{
			mTransferFunctionRangeMin[pRenderLayerIndex] = (float) clamp(	pTransferRangeMin,
																																		0,
																																		1);
			mTransferFunctionRangeMax[pRenderLayerIndex] = (float) clamp(	pTransferRangeMax,
																																		0,
																																		1);
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Sets transfer range minimum, must be within [0,1].
	 *
	 * @param pTransferRangeMin
	 *          minimum
	 */
	@Override
	public void setTransferFunctionRangeMin(final double pTransferRangeMin)
	{
		setTransferFunctionRangeMin(getCurrentRenderLayerIndex(),
																pTransferRangeMin);
	}

	/**
	 * Sets transfer range minimum, must be within [0,1].
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pTransferRangeMin
	 *          transfer range min
	 */
	@Override
	public void setTransferFunctionRangeMin(final int pRenderLayerIndex,
																					final double pTransferRangeMin)
	{
		getDisplayLock().lock();
		try
		{
			mTransferFunctionRangeMin[pRenderLayerIndex] = (float) clamp(	pTransferRangeMin,
																																		0,
																																		1);

			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	}

	/**
	 * Sets transfer function range maximum, must be within [0,1].
	 *
	 * @param pTransferRangeMax
	 *          maximum
	 */
	@Override
	public void setTransferFunctionRangeMax(final double pTransferRangeMax)
	{
		setTransferFunctionRangeMax(getCurrentRenderLayerIndex(),
																pTransferRangeMax);
	}

	/**
	 * Sets transfer function range maximum, must be within [0,1].
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pTransferRangeMax
	 *          transfer range max
	 */
	@Override
	public void setTransferFunctionRangeMax(final int pRenderLayerIndex,
																					final double pTransferRangeMax)
	{
		getDisplayLock().lock();
		try
		{
			mTransferFunctionRangeMax[pRenderLayerIndex] = (float) clamp(	pTransferRangeMax,
																																		0,
																																		1);
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#addTranslationX(double)
	 */
	@Override
	public void addTranslationX(final double pDX)
	{
		getDisplayLock().lock();
		try
		{
			setTranslationX(getTranslationX() + pDX);
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#addTranslationY(double)
	 */
	@Override
	public void addTranslationY(final double pDY)
	{
		getDisplayLock().lock();
		try
		{
			setTranslationY(getTranslationY() + pDY);
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#addTranslationZ(double)
	 */
	@Override
	public void addTranslationZ(final double pDZ)
	{
		getDisplayLock().lock();
		try
		{
			setTranslationZ(getTranslationZ() + pDZ);
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	}

	@Override
	public Quaternion getQuaternion()
	{
		return new Quaternion(mRotationQuaternion);
	}

	@Override
	public void setQuaternion(Quaternion pQuaternion)
	{
		getDisplayLock().lock();
		try
		{
			mRotationQuaternion.set(pQuaternion);
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationX()
	 */
	@Override
	public void setTranslationX(double pTranslationX)
	{
		getDisplayLock().lock();
		try
		{
			mTranslationX = (float) pTranslationX;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}

	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationY()
	 */
	@Override
	public void setTranslationY(double pTranslationY)
	{
		getDisplayLock().lock();
		try
		{
			mTranslationY = (float) pTranslationY;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationZ()
	 */
	@Override
	public void setTranslationZ(double pTranslationZ)
	{
		getDisplayLock().lock();
		try
		{
			mTranslationZ = (float) pTranslationZ;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationZ()
	 */
	@Override
	public void setDefaultTranslationZ()
	{
		setTranslationZ(-4 / getFOV());
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationX()
	 */
	@Override
	public float getTranslationX()
	{
		return mTranslationX;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationY()
	 */
	@Override
	public float getTranslationY()
	{
		return mTranslationY;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationZ()
	 */
	@Override
	public float getTranslationZ()
	{
		return mTranslationZ;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationZ()
	 */
	@Override
	public void setFOV(double pFOV)
	{
		getDisplayLock().lock();
		try
		{
			final double lNewFOV = min(cMaximalFOV, max(cMinimalFOV, pFOV));
			final double lFactor = mFOV / lNewFOV;
			/*System.out.format("old:%f new%f factor=%f \n",
												mFOV,
												lNewFOV,
												lFactor);/**/
			mFOV = (float) lNewFOV;
			setTranslationZ(lFactor * getTranslationZ());
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationZ()
	 */
	@Override
	public float getFOV()
	{
		return mFOV;
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTranslationZ()
	 */
	@Override
	public void addFOV(double pDelta)
	{
		setFOV(mFOV + pDelta);
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#setTransferFunction(clearvolume.transferf.TransferFunction)
	 */
	@Override
	public void setTransferFunction(final TransferFunction pTransfertFunction)
	{
		setTransferFunction(getCurrentRenderLayerIndex(),
												pTransfertFunction);
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#setTransferFunction(int,
	 *      clearvolume.transferf.TransferFunction)
	 */
	@Override
	public void setTransferFunction(final int pRenderLayerIndex,
																	final TransferFunction pTransfertFunction)
	{
		getDisplayLock().lock();
		try
		{
			mTransferFunctions[pRenderLayerIndex] = pTransfertFunction;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/* (non-Javadoc)
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#getTransferFunction(int)
	 */
	@Override
	public TransferFunction getTransferFunction(final int pRenderLayerIndex)
	{
		return mTransferFunctions[pRenderLayerIndex];
	}

	/**
	 * Returns currently used transfer function.
	 *
	 * @return currently used transfer function
	 */
	@Override
	public TransferFunction getTransferFunction()
	{
		return mTransferFunctions[getCurrentRenderLayerIndex()];
	}

	/**
	 * Returns currently used transfer function.
	 *
	 * @return currently used transfer function
	 */
	@Override
	public float[] getTransferFunctionArray()
	{
		return mTransferFunctions[getCurrentRenderLayerIndex()].getArray();
	}

	/**
	 * Returns currently used mProjectionMatrix algorithm.
	 *
	 * @return currently used mProjectionMatrix algorithm
	 */
	@Override
	public RenderAlgorithm getRenderAlgorithm(final int pRenderLayerIndex)
	{
		return mRenderAlgorithm[pRenderLayerIndex];
	}

	/* (non-Javadoc)
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#setRenderAlgorithm(int, clearvolume.renderer.RenderAlgorithm)
	 */
	@Override
	public void setRenderAlgorithm(	final int pRenderLayerIndex,
																	final RenderAlgorithm pRenderAlgorithm)
	{
		getDisplayLock().lock();
		try
		{
			mRenderAlgorithm[pRenderLayerIndex] = pRenderAlgorithm;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/* (non-Javadoc)
	 * @see clearvolume.renderer.ClearVolumeRendererInterface#setRenderAlgorithm(int, clearvolume.renderer.RenderAlgorithm)
	 */
	@Override
	public void setRenderAlgorithm(final RenderAlgorithm pRenderAlgorithm)
	{
		getDisplayLock().lock();
		try
		{
			for (int i = 0; i < mRenderAlgorithm.length; i++)
				mRenderAlgorithm[i] = pRenderAlgorithm;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Cycles through rendering algorithms for all layers
	 */
	@Override
	public void cycleRenderAlgorithm()
	{
		getDisplayLock().lock();
		try
		{
			int i = 0;
			for (final RenderAlgorithm lRenderAlgorithm : mRenderAlgorithm)
				mRenderAlgorithm[i++] = lRenderAlgorithm.next();
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Cycles through rendering algorithms for all layers
	 */
	@Override
	public void cycleRenderAlgorithm(int pRenderLayerIndex)
	{
		getDisplayLock().lock();
		try
		{
			mRenderAlgorithm[pRenderLayerIndex] = mRenderAlgorithm[pRenderLayerIndex].next();
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Returns for a given index the corresponding volume data buffer.
	 *
	 * @return data buffer for a given render layer.
	 */
	public boolean isNewVolumeDataAvailable()
	{
		for (final FragmentedMemoryInterface lFragmentedMemoryInterface : mVolumeDataByteBuffers)
			if (lFragmentedMemoryInterface != null)
				return true;
		return false;
	}

	/**
	 * Returns for a given index the corresponding volume data buffer.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @return data buffer for a given render layer.
	 */
	public FragmentedMemoryInterface getVolumeDataBuffer(final int pRenderLayerIndex)
	{
		return mVolumeDataByteBuffers[pRenderLayerIndex];
	}

	/**
	 * Clears volume data buffer.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 */
	public void clearVolumeDataBufferReference(final int pRenderLayerIndex)
	{
		mVolumeDataByteBuffers[pRenderLayerIndex] = null;
	}

	/**
	 * Returns object used for locking volume data copy for a given layer.
	 *
	 * @param pRenderLayerIndex
	 *          render layer index
	 *
	 * @return locking object
	 */
	public Object getSetVolumeDataBufferLock(final int pRenderLayerIndex)
	{
		return mSetVolumeDataBufferLocks[pRenderLayerIndex];
	}

	/**
	 * Reset rotation and translation.
	 */
	@Override
	public void resetRotationTranslation()
	{
		getDisplayLock().lock();
		try
		{
			mRotationQuaternion.setIdentity();
			setTranslationX(0);
			setTranslationY(0);
			setDefaultTranslationZ();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Sets current render layer.
	 * 
	 * @param pRenderLayerIndex
	 *          current render layer index
	 */
	@Override
	public void setCurrentRenderLayer(final int pRenderLayerIndex)
	{
		mCurrentRenderLayerIndex = pRenderLayerIndex;
	}

	/**
	 * Returns current render layer.
	 * 
	 * @return current render layer index
	 */
	@Override
	public int getCurrentRenderLayerIndex()
	{
		return mCurrentRenderLayerIndex;
	}

	/**
	 * Returns current render layer.
	 * 
	 * @param pNumberOfRenderLayers
	 *          number of render layers
	 */
	@Override
	public void setNumberOfRenderLayers(final int pNumberOfRenderLayers)
	{
		mNumberOfRenderLayers = pNumberOfRenderLayers;
	}

	/**
	 * Returns number of render layers.
	 * 
	 * @return current render layer index
	 */
	@Override
	public int getNumberOfRenderLayers()
	{
		return mNumberOfRenderLayers;
	}

	/**
	 * Sets the voxel size.
	 * 
	 * @param pVoxelSizeX
	 *          voxel size along X
	 * @param pVoxelSizeY
	 *          voxel size along Y
	 * @param pVoxelSizeZ
	 *          voxel size along Z
	 * 
	 */
	@Override
	public void setVoxelSize(	final double pVoxelSizeX,
														final double pVoxelSizeY,
														final double pVoxelSizeZ)
	{
		getDisplayLock().lock();
		try
		{
			mVoxelSizeX = pVoxelSizeX;
			mVoxelSizeY = pVoxelSizeY;
			mVoxelSizeZ = pVoxelSizeZ;
			notifyChangeOfVolumeRenderingParameters();
		}
		finally
		{
			if (getDisplayLock().isHeldByCurrentThread())
				getDisplayLock().unlock();
		}
	}

	/**
	 * Sets volume data buffer. Voxels are assumed to be isotropic.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pByteBuffer
	 *          byte buffer
	 * @param pVolumeSizeX
	 *          size in voxels along X
	 * @param pVolumeSizeY
	 *          size in voxels along Y
	 * @param pVolumeSizeZ
	 *          size in voxels along Z
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	final int pRenderLayerIndex,
																			final ByteBuffer pByteBuffer,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ)
	{
		return setVolumeDataBuffer(	pRenderLayerIndex,
																pByteBuffer,
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																1,
																1,
																1);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pByteBuffer
	 *          byte buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * @param pVoxelSizeX
	 *          voxel dimension along X
	 * @param pVoxelSizeY
	 *          voxel dimension along Y
	 * @param pVoxelSizeZ
	 *          voxel dimension along Z
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	int pRenderLayerIndex,
																			ByteBuffer pByteBuffer,
																			long pVolumeSizeX,
																			long pVolumeSizeY,
																			long pVolumeSizeZ,
																			double pVoxelSizeX,
																			double pVoxelSizeY,
																			double pVoxelSizeZ)
	{
		return setVolumeDataBuffer(	cDefaultSetVolumeDataBufferTimeout,
																TimeUnit.SECONDS,
																pRenderLayerIndex,
																pByteBuffer,
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																pVoxelSizeX,
																pVoxelSizeY,
																pVoxelSizeZ);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pTimeOut
	 *          time out duration
	 * @param pTimeUnit
	 *          time unit for time out duration
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pByteBuffer
	 *          byte buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	long pTimeOut,
																			TimeUnit pTimeUnit,
																			final int pRenderLayerIndex,
																			final ByteBuffer pByteBuffer,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ)
	{
		return setVolumeDataBuffer(	pTimeOut,
																pTimeUnit,
																pRenderLayerIndex,
																pByteBuffer,
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																1,
																1,
																1);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pFragmentedMemoryInterface
	 *          fragmented buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	final int pRenderLayerIndex,
																			final FragmentedMemoryInterface pFragmentedMemoryInterface,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ)
	{
		return setVolumeDataBuffer(	pRenderLayerIndex,
																pFragmentedMemoryInterface,
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																1,
																1,
																1);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pContiguousMemoryInterface
	 *          contguous buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	final int pRenderLayerIndex,
																			final ContiguousMemoryInterface pContiguousMemoryInterface,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ)
	{
		return setVolumeDataBuffer(	pRenderLayerIndex,
																FragmentedMemory.wrap(pContiguousMemoryInterface),
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																1,
																1,
																1);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render layer index
	 * @param pContiguousMemoryInterface
	 *          contiguous buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * @param pVoxelSizeX
	 *          voxel dimension along X
	 * @param pVoxelSizeY
	 *          voxel dimension along Y
	 * @param pVoxelSizeZ
	 *          voxel dimension along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	final int pRenderLayerIndex,
																			final ContiguousMemoryInterface pContiguousMemoryInterface,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ,
																			final double pVoxelSizeX,
																			final double pVoxelSizeY,
																			final double pVoxelSizeZ)
	{
		return setVolumeDataBuffer(	pRenderLayerIndex,
																FragmentedMemory.wrap(pContiguousMemoryInterface),
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																pVoxelSizeX,
																pVoxelSizeY,
																pVoxelSizeZ);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render pByteBuffer index
	 * @param pByteBuffer
	 *          NIO byte buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * @param pVoxelSizeX
	 *          voxel dimension along X
	 * @param pVoxelSizeY
	 *          voxel dimension along Y
	 * @param pVoxelSizeZ
	 *          voxel dimension along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	long pTimeOut,
																			TimeUnit pTimeUnit,
																			final int pRenderLayerIndex,
																			final ByteBuffer pByteBuffer,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ,
																			final double pVoxelSizeX,
																			final double pVoxelSizeY,
																			final double pVoxelSizeZ)
	{

		FragmentedMemoryInterface lFragmentedMemoryInterface;
		if (!pByteBuffer.isDirect())
		{
			final OffHeapMemory lOffHeapMemory = new OffHeapMemory(pByteBuffer.capacity());
			lOffHeapMemory.copyFrom(pByteBuffer);
			lFragmentedMemoryInterface = FragmentedMemory.wrap(lOffHeapMemory);
		}
		else
		{
			final OffHeapMemory lOffHeapMemory = OffHeapMemory.wrapBuffer(pByteBuffer);
			lFragmentedMemoryInterface = FragmentedMemory.wrap(lOffHeapMemory);
		}

		return setVolumeDataBuffer(	pTimeOut,
																pTimeUnit,
																pRenderLayerIndex,
																lFragmentedMemoryInterface,
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																pVoxelSizeX,
																pVoxelSizeY,
																pVoxelSizeZ);

	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render pByteBuffer index
	 * @param pVolume
	 *          volume
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	final int pRenderLayerIndex,
																			final Volume pVolume)
	{
		return setVolumeDataBuffer(	pRenderLayerIndex,
																pVolume.getDataBuffer(),
																pVolume.getWidthInVoxels(),
																pVolume.getHeightInVoxels(),
																pVolume.getDepthInVoxels(),
																pVolume.getVoxelWidthInRealUnits(),
																pVolume.getVoxelHeightInRealUnits(),
																pVolume.getVoxelDepthInRealUnits());
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render pByteBuffer index
	 * @param pFragmentedMemoryInterface
	 *          fragmented buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * @param pVoxelSizeX
	 *          voxel dimension along X
	 * @param pVoxelSizeY
	 *          voxel dimension along Y
	 * @param pVoxelSizeZ
	 *          voxel dimension along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	final int pRenderLayerIndex,
																			final FragmentedMemoryInterface pFragmentedMemoryInterface,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ,
																			final double pVoxelSizeX,
																			final double pVoxelSizeY,
																			final double pVoxelSizeZ)
	{
		return setVolumeDataBuffer(	cDefaultSetVolumeDataBufferTimeout,
																TimeUnit.SECONDS,
																pRenderLayerIndex,
																pFragmentedMemoryInterface,
																pVolumeSizeX,
																pVolumeSizeY,
																pVolumeSizeZ,
																pVoxelSizeX,
																pVoxelSizeY,
																pVoxelSizeZ);
	}

	/**
	 * Sets volume data buffer.
	 * 
	 * @param pRenderLayerIndex
	 *          render pByteBuffer index
	 * @param pFragmentedMemoryInterface
	 *          fragmented buffer
	 * @param pVolumeSizeX
	 *          volume size in voxels along X
	 * @param pVolumeSizeY
	 *          volume size in voxels along Y
	 * @param pVolumeSizeZ
	 *          volume size in voxels along Z
	 * @param pVoxelSizeX
	 *          voxel dimension along X
	 * @param pVoxelSizeY
	 *          voxel dimension along Y
	 * @param pVoxelSizeZ
	 *          voxel dimension along Z
	 * 
	 * 
	 * @return true if transfer was completed (no time out)
	 */
	@Override
	public boolean setVolumeDataBuffer(	long pTimeOut,
																			TimeUnit pTimeUnit,
																			final int pRenderLayerIndex,
																			final FragmentedMemoryInterface pFragmentedMemoryInterface,
																			final long pVolumeSizeX,
																			final long pVolumeSizeY,
																			final long pVolumeSizeZ,
																			final double pVoxelSizeX,
																			final double pVoxelSizeY,
																			final double pVoxelSizeZ)
	{
		synchronized (getSetVolumeDataBufferLock(pRenderLayerIndex))
		{
			final long lSizeInBytes = pFragmentedMemoryInterface.getSizeInBytes();
			if (getMax3DBufferSize() < lSizeInBytes)
			{
				throw new VolumeTooBigException("The volume data is too big to fit the rendering device (volume size: " + lSizeInBytes
																				/ 1024.0
																				/ 1024.0
																				+ " MB, free memory on device: "
																				+ getMax3DBufferSize()
																				/ 1024.0
																				/ 1024.0
																				+ ").");
			}
			else if (pVolumeSizeX > getMaxVolumeWidth() || pVolumeSizeY > getMaxVolumeHeight()
								|| pVolumeSizeZ > getMaxVolumeDepth())
			{
				throw new VolumeTooBigException(String.format("The volume dimensions are too big to fit the rendering device (volume dimensions: (%d,%d,%d), max supported dimensions by your device: (%d,%d,%d)) ",
																											pVolumeSizeX,
																											pVolumeSizeY,
																											pVolumeSizeZ,
																											getMaxVolumeWidth(),
																											getMaxVolumeHeight(),
																											getMaxVolumeDepth()));
			}

			if (mVolumeSizeX != pVolumeSizeX || mVolumeSizeY != pVolumeSizeY
					|| mVolumeSizeZ != pVolumeSizeZ)
			{
				mVolumeDimensionsChanged = true;
			}

			mVolumeSizeX = pVolumeSizeX;
			mVolumeSizeY = pVolumeSizeY;
			mVolumeSizeZ = pVolumeSizeZ;

			mVoxelSizeX = pVoxelSizeX;
			mVoxelSizeY = pVoxelSizeY;
			mVoxelSizeZ = pVoxelSizeZ;

			clearCompletionOfDataBufferCopy(pRenderLayerIndex);
			mVolumeDataByteBuffers[pRenderLayerIndex] = pFragmentedMemoryInterface;

			notifyChangeOfVolumeRenderingParameters();
		}

		// System.out.print("Waiting...");
		final boolean lWaitResult = waitToFinishDataBufferCopy(	pRenderLayerIndex,
																														pTimeOut,
																														pTimeUnit);
		// if (!lWaitResult)
		// System.err.println("TIMEOUT!");
		// System.out.println(" finished!");

		return lWaitResult;
	}

	@Override
	public abstract long getMaxVolumeWidth();

	@Override
	public abstract long getMaxVolumeHeight();

	@Override
	public abstract long getMaxVolumeDepth();

	@Override
	public abstract long getMax3DBufferSize();

	@Override
	public VolumeManager createCompatibleVolumeManager(final int pMaxAvailableVolumes)
	{
		return new VolumeManager(pMaxAvailableVolumes);
	}

	/**
	 * Notifies the volume data copy completion.
	 */
	protected void notifyCompletionOfDataBufferCopy(final int pRenderLayerIndex)
	{
		mDataBufferCopyIsFinishedArray[pRenderLayerIndex].countDown();
	}

	/**
	 * Clears data copy buffer flag.
	 */
	protected void clearCompletionOfDataBufferCopy(final int pRenderLayerIndex)
	{
		mDataBufferCopyIsFinishedArray[pRenderLayerIndex] = new CountDownLatch(1);
	}

	/**
	 * Waits until volume data copy completes all layers.
	 *
	 * @return true is completed, false if it timed-out.
	 */
	@Override
	public boolean waitToFinishAllDataBufferCopy(	final long pTimeOut,
																								final TimeUnit pTimeUnit)
	{
		boolean lNoTimeOut = true;
		for (int i = 0; i < getNumberOfRenderLayers(); i++)
			lNoTimeOut &= waitToFinishDataBufferCopy(	getCurrentRenderLayerIndex(),
																								pTimeOut,
																								pTimeUnit);

		return lNoTimeOut;
	}

	/**
	 * Waits until volume data copy completes for a given layer
	 *
	 * @return true is completed, false if it timed-out.
	 */
	@Override
	public boolean waitToFinishDataBufferCopy(final int pRenderLayerIndex,
																						final long pTimeOut,
																						final TimeUnit pTimeUnit)

	{
		try
		{
			// final long lStartNs = System.nanoTime();
			if (mDataBufferCopyIsFinishedArray[pRenderLayerIndex] == null)
				return true;
			final boolean lAwaitResult = mDataBufferCopyIsFinishedArray[pRenderLayerIndex].await(	pTimeOut,
																																														pTimeUnit);
			// final long lStopNs = System.nanoTime();
			// System.out.println("ELPASED:" + (lStopNs - lStartNs) / 1.0e6);
			return lAwaitResult;
		}
		catch (final InterruptedException e)
		{
			return waitToFinishDataBufferCopy(pRenderLayerIndex,
																				pTimeOut,
																				pTimeUnit);
		}
	}

	/**
	 * Adds a rotation controller.
	 *
	 * @param pRotationControllerInterface
	 *          rotation controller
	 */
	@Override
	public void addRotationController(RotationControllerInterface pRotationControllerInterface)
	{
		mRotationControllerList.add(pRotationControllerInterface);
	}

	/**
	 * Removes a rotation controller.
	 *
	 * @param pRotationControllerInterface
	 *          rotation controller
	 */
	@Override
	public void removeRotationController(RotationControllerInterface pRotationControllerInterface)
	{
		mRotationControllerList.remove(pRotationControllerInterface);
	}

	/**
	 * Returns the auto rotation controller.
	 *
	 * @return auto rotation controller
	 */
	@Override
	public AutoRotationController getAutoRotateController()
	{
		return mAutoRotationController;
	}

	/**
	 * Returns the current list of rotation controllers.
	 *
	 * @return currently used rotation controller.
	 */
	@Override
	public ArrayList<RotationControllerInterface> getRotationControllers()
	{
		return mRotationControllerList;
	}

	/**
	 * Toggles the display of the Control Frame;
	 */
	@Override
	public void toggleControlPanelDisplay()
	{
		if (mControlFrame != null)
			mControlFrame.setVisible(!mControlFrame.isVisible());
	}

	/**
	 * Toggles the display of the Control Frame;
	 */
	@Override
	public void addProcessor(final Processor<?> pProcessor)
	{
		mProcessorsMap.put(pProcessor.getName(), pProcessor);
	}

	/**
	 * Toggles the display of the Control Frame;
	 */
	@Override
	public void addProcessors(final Collection<Processor<?>> pProcessors)
	{
		for (final Processor<?> lProcessor : pProcessors)
			addProcessor(lProcessor);
	}

	@Override
	public Collection<Processor<?>> getProcessors()
	{
		return mProcessorsMap.values();
	}

	/**
	 * Toggles the display of the Control Frame;
	 */
	@Override
	public void addVolumeCaptureListener(final VolumeCaptureListener pVolumeCaptureListener)
	{
		if (pVolumeCaptureListener != null)
			mVolumeCaptureListenerList.add(pVolumeCaptureListener);
	}

	public void notifyVolumeCaptureListeners(	ByteBuffer[] pCaptureBuffer,
																						NativeTypeEnum pNativeType,
																						long pVolumeWidth,
																						long pVolumeHeight,
																						long pVolumeDepth,
																						double pVoxelWidth,
																						double pVoxelHeight,
																						double pVoxelDepth)
	{
		for (final VolumeCaptureListener lVolumeCaptureListener : mVolumeCaptureListenerList)
		{
			lVolumeCaptureListener.capturedVolume(pCaptureBuffer,
																						pNativeType,
																						pVolumeWidth,
																						pVolumeHeight,
																						pVolumeDepth,
																						pVoxelWidth,
																						pVoxelHeight,
																						pVoxelDepth);
		}
	}

	/**
	 * Requests capture of the current displayed volume (Preferably of all layers
	 * but possibly just of the current layer.)
	 */
	@Override
	public void requestVolumeCapture()
	{
		mVolumeCaptureFlag = true;
		requestDisplay();
	};

	@Override
	public void setAdaptiveLODActive(boolean pAdaptiveLOD)
	{
		if (mAdaptiveLODController != null)
			mAdaptiveLODController.setActive(pAdaptiveLOD);
	}

	@Override
	public boolean getAdaptiveLODActive()
	{
		return getAdaptiveLODController().isActive();
	}

	/**
	 * Returns the Adaptive level-of-detail(LOD) controller.
	 * 
	 * @return LOD controller
	 */
	@Override
	public AdaptiveLODController getAdaptiveLODController()
	{
		return mAdaptiveLODController;
	}

	/**
	 * Toggle on/off the adaptive Level-Of-Detail engine
	 */
	@Override
	public void toggleAdaptiveLOD()
	{
		setAdaptiveLODActive(!getAdaptiveLODActive());
	}

	/**
	 * Adds a eye ray listener to this renderer.
	 *
	 * @param pEyeRayListener
	 *          eye ray listener
	 */
	@Override
	public void addEyeRayListener(EyeRayListener pEyeRayListener)
	{
		mEyeRayListenerList.add(pEyeRayListener);
	}

	/**
	 * Removes a eye ray listener to this renderer.
	 *
	 * @param pEyeRayListener
	 *          eye ray listener
	 */
	@Override
	public void removeEyeRayListener(EyeRayListener pEyeRayListener)
	{
		mEyeRayListenerList.remove(pEyeRayListener);
	}

	@Override
	public ReentrantLock getDisplayLock()
	{
		return mDisplayReentrantLock;
	}

	/**
	 * Clamps the value pValue to the interval [pMin,pMax]
	 *
	 * @param pValue
	 *          to be clamped
	 * @param pMin
	 *          minimum
	 * @param pMax
	 *          maximum
	 * @return clamped size
	 */
	public static double clamp(	final double pValue,
															final double pMin,
															final double pMax)
	{
		return Math.min(Math.max(pValue, pMin), pMax);
	}

	@Override
	public void close()
	{
		if (mControlFrame != null)
			try
			{
				SwingUtilities.invokeAndWait(new Runnable()
				{

					@Override
					public void run()
					{
						if (mControlFrame != null)
							try
							{
								mControlFrame.dispose();
								mControlFrame = null;
							}
							catch (final Throwable e)
							{
								e.printStackTrace();
							}
					}
				});
			}
			catch (final Throwable e)
			{
				e.printStackTrace();
			}

	}

}
