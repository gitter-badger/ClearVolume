package clearvolume.renderer.clearcuda;

/*
 * JCuda - Java bindings for NVIDIA CUDA driver and runtime API
 * http://www.jcuda.org
 *
 * Copyright 2009-2011 Marco Hutter - http://www.jcuda.org
 */

import static java.lang.Math.max;
import static java.lang.Math.pow;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jcuda.CudaException;
import jcuda.Pointer;
import jcuda.driver.CUaddress_mode;
import jcuda.driver.CUfilter_mode;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import clearcuda.CudaArray;
import clearcuda.CudaCompiler;
import clearcuda.CudaContext;
import clearcuda.CudaDevice;
import clearcuda.CudaDevicePointer;
import clearcuda.CudaFunction;
import clearcuda.CudaModule;
import clearcuda.CudaTextureReference;
import clearvolume.renderer.cleargl.ClearGLVolumeRenderer;
import clearvolume.renderer.processors.CUDAProcessor;
import clearvolume.renderer.processors.Processor;

import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;

import coremem.ContiguousMemoryInterface;
import coremem.fragmented.FragmentedMemoryInterface;
import coremem.types.NativeTypeEnum;
import coremem.util.Size;

/**
 * Class JCudaClearVolumeRenderer
 *
 * Implements a JOGLPBOClearVolumeRenderer usin a CUDA kernel for rendering the
 * 2D mProjectionMatrix.
 *
 * @author Loic Royer 2014
 *
 */
public class JCudaClearVolumeRenderer extends ClearGLVolumeRenderer	implements
																																		GLEventListener
{

	private static final int cBlockSize = 32;

	/**
	 * CUDA context.
	 */
	private CudaDevice mCudaDevice;

	/**
	 * CUDA context.
	 */
	private CudaContext mCudaContext;

	/**
	 * CUDA module.
	 */
	private CudaModule mCudaModule;

	/**
	 * Volume rendering CUDA function
	 */
	private CudaFunction mCurrentRenderKernel,
			mMaxProjectionRenderKernel, mIsoSurfaceRenderKernel;

	/**
	 * We use these buffers when rendering to standard CUDA buffers.
	 */
	private volatile CudaDevicePointer[] mCudaBufferDevicePointer;

	/**
	 * And use this temporary buffer on the CPU ram:
	 */
	private volatile ByteBuffer mTemporaryTransfertBuffer;

	/**
	 * CUDA Device pointers to the device itself, which are in constant memory:
	 * inverted mViewMatrix-matrix, mProjectionMatrix matrix, transfer function.
	 */

	private CudaDevicePointer mInvertedModelViewMatrix,
			mInvertedProjectionMatrix, mSizeOfTransferFunction,
			mVolumeArrayWidth, mVolumeArrayHeight, mVolumeArrayDepth;

	/**
	 * CUDA arrays to the transfer function and volume data.
	 */
	private CudaArray[] mTransferFunctionCudaArrays = new CudaArray[1];
	private CudaArray[] mVolumeDataCudaArrays = new CudaArray[1];

	/**
	 * CUDA references to the transfer function and volume data textures.
	 */
	private CudaTextureReference mVolumeDataCudaTexture;
	private CudaTextureReference mTransferFunctionTexture;

	/**
	 * Pointer to kernel parameters.
	 */
	private Pointer mKernelParametersPointer;

	/**
	 * Constructs an instance of the JCudaClearVolumeRenderer class given a window
	 * name, width and height.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 */
	public JCudaClearVolumeRenderer(final String pWindowName,
																	final int pWindowWidth,
																	final int pWindowHeight)
	{
		super("[CUDA] " + pWindowName, pWindowWidth, pWindowHeight);
	}

	/**
	 * Constructs an instance of the JCudaClearVolumeRenderer class given a window
	 * name, width, height, and bytes=per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 */
	public JCudaClearVolumeRenderer(final String pWindowName,
																	final int pWindowWidth,
																	final int pWindowHeight,
																	final NativeTypeEnum pNativeTypeEnum)
	{
		super("[CUDA] " + pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum);
	}

	/**
	 * Constructs an instance of the JCudaClearVolumeRenderer class given a window
	 * name, width, height, and bytes=per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxRenderWidth
	 *          max render width
	 * @param pMaxRenderHeight
	 *          max render height
	 */
	public JCudaClearVolumeRenderer(final String pWindowName,
																	final int pWindowWidth,
																	final int pWindowHeight,
																	final NativeTypeEnum pNativeTypeEnum,
																	final int pMaxRenderWidth,
																	final int pMaxRenderHeight)
	{
		super("[CUDA] " + pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum,
					pMaxRenderWidth,
					pMaxRenderHeight);
	}

	/**
	 * Constructs an instance of the JCudaClearVolumeRenderer class given a window
	 * name, width, height, and bytes=per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxRenderWidth
	 *          max render width
	 * @param pMaxRenderHeight
	 *          max render height
	 * @param pNumberOfRenderLayers
	 *          number of render layers
	 * @param pUseInCanvas
	 *          true if the renderer is to be used as part of a AWT/Swing/SWT
	 *          component.
	 */
	public JCudaClearVolumeRenderer(final String pWindowName,
																	final Integer pWindowWidth,
																	final Integer pWindowHeight,
																	final String pNativeTypeEnum,
																	final Integer pMaxRenderWidth,
																	final Integer pMaxRenderHeight,
																	final Integer pNumberOfRenderLayers,
																	final boolean pUseInCanvas)
	{
		this(	pWindowName,
					pWindowWidth,
					pWindowHeight,
					NativeTypeEnum.valueOf(pNativeTypeEnum),
					pMaxRenderWidth,
					pMaxRenderHeight,
					pNumberOfRenderLayers,
					pUseInCanvas);
	}

	/**
	 * Constructs an instance of the JCudaClearVolumeRenderer class given a window
	 * name, width, height, and bytes=per-voxel.
	 *
	 * @param pWindowName
	 *          window name
	 * @param pWindowWidth
	 *          window width
	 * @param pWindowHeight
	 *          window height
	 * @param pNativeTypeEnum
	 *          native type
	 * @param pMaxRenderWidth
	 *          max render width
	 * @param pMaxRenderHeight
	 *          max render height
	 * @param pNumberOfRenderLayers
	 *          number of render layers
	 * @param pUseInCanvas
	 *          true if the renderer is to be used as part of a AWT/Swing/SWT
	 *          component.
	 */
	public JCudaClearVolumeRenderer(final String pWindowName,
																	final Integer pWindowWidth,
																	final Integer pWindowHeight,
																	final NativeTypeEnum pNativeTypeEnum,
																	final Integer pMaxRenderWidth,
																	final Integer pMaxRenderHeight,
																	final Integer pNumberOfRenderLayers,
																	final boolean pUseInCanvas)
	{
		super("[CUDA] " + pWindowName,
					pWindowWidth,
					pWindowHeight,
					pNativeTypeEnum,
					pMaxRenderWidth,
					pMaxRenderHeight,
					pNumberOfRenderLayers,
					pUseInCanvas);

		mTransferFunctionCudaArrays = new CudaArray[pNumberOfRenderLayers];
		mVolumeDataCudaArrays = new CudaArray[pNumberOfRenderLayers];

	}

	/**
	 * Initialises CUDA and the 3D texture with the current volume data.
	 *
	 * @throws IOException
	 */
	@Override
	protected boolean initVolumeRenderer()
	{
		try
		{
			mCudaDevice = CudaDevice.getBestCudaDevice();

			printMemoryState();

			assert (mCudaContext == null);
			mCudaContext = new CudaContext(mCudaDevice, true);

			final Class<?> lRootClass = JCudaClearVolumeRenderer.class;

			final File lPTXFile = compileCUDA(lRootClass);

			mCudaModule = CudaModule.moduleFromPTX(lPTXFile);

			mInvertedModelViewMatrix = mCudaModule.getGlobal("c_invViewMatrix");
			mInvertedProjectionMatrix = mCudaModule.getGlobal("c_invProjectionMatrix");
			mSizeOfTransferFunction = mCudaModule.getGlobal("c_sizeOfTransfertFunction");

			mVolumeArrayWidth = mCudaModule.getGlobal("c_volumeArrayWidth");
			mVolumeArrayHeight = mCudaModule.getGlobal("c_volumeArrayHeight");
			mVolumeArrayDepth = mCudaModule.getGlobal("c_volumeArrayDepth");

			mMaxProjectionRenderKernel = mCudaModule.getFunction("maxproj_render");
			mIsoSurfaceRenderKernel = mCudaModule.getFunction("isosurface_render");

			for (int i = 0; i < getNumberOfRenderLayers(); i++)
				prepareVolumeDataArray(i, null);

			prepareVolumeDataTexture();

			for (int i = 0; i < getNumberOfRenderLayers(); i++)
			{
				prepareTransferFunctionArray(i);
				copyTransferFunctionArray(i);
			}
			prepareTransferFunctionTexture();

			mCudaBufferDevicePointer = new CudaDevicePointer[getNumberOfRenderLayers()];

			for (final Processor<?> lProcessor : mProcessorsMap.values())
				if (lProcessor.isCompatibleProcessor(getClass()))
					if (lProcessor instanceof CUDAProcessor)
					{
						final CUDAProcessor<?> lCUDAProcessor = (CUDAProcessor<?>) lProcessor;
						lCUDAProcessor.setDeviceAndContext(	mCudaDevice,
																								mCudaContext);
					}

			return true;
		}
		catch (final IOException e)
		{
			e.printStackTrace();
			return false;
		}
	}

	@Override
	protected void notifyChangeOfTextureDimensions()
	{
		for (int i = 0; i < getNumberOfRenderLayers(); i++)
		{
			final long lBufferSize = 4 * getRenderWidth()
																* getRenderHeight();

			if (mCudaBufferDevicePointer[i] != null)
				mCudaBufferDevicePointer[i].close();

			mCudaBufferDevicePointer[i] = CudaDevicePointer.malloc(lBufferSize);
		}
	}

	private void printMemoryState()
	{
		final long lTotalMem = mCudaDevice.getTotalMem();
		final long lAvailableMem = mCudaDevice.getAvailableMem();
		System.out.format("CUDA memory:  %d / %d (free/total) ratio=%g \n",
											lAvailableMem,
											lTotalMem,
											(1.0 * lAvailableMem) / lTotalMem);
	}

	private File compileCUDA(final Class<?> lRootClass) throws IOException
	{
		File lPTXFile;

		try
		{
			final CudaCompiler lCudaCompiler = new CudaCompiler(mCudaDevice,
																													lRootClass.getSimpleName());

			lCudaCompiler.setParameter(	Pattern.quote("/*BytesPerVoxel*/"),
																	"" + getBytesPerVoxel());

			final File lCUFile = lCudaCompiler.addFile(	lRootClass,
																									"kernels/VolumeRender.cu",
																									true);

			lCudaCompiler.addFiles(	CudaCompiler.class,
															true,
															"includes/helper_cuda.h",
															"includes/helper_math.h",
															"includes/helper_string.h");

			lPTXFile = lCudaCompiler.compile(lCUFile);
		}
		catch (final Exception e)
		{

			final InputStream lInputStreamPTXFile = lRootClass.getResourceAsStream("kernels/VolumeRender.backup.ptx");
			final StringWriter lStringWriter = new StringWriter();
			IOUtils.copy(	lInputStreamPTXFile,
										lStringWriter,
										Charset.defaultCharset());

			lPTXFile = File.createTempFile(	this.getClass().getSimpleName(),
																			".ptx");
			FileUtils.write(lPTXFile, lStringWriter.toString());

		}

		return lPTXFile;
	}

	/**
	 * Allocates, configures and copies 3D volume data.
	 */
	private void prepareVolumeDataArray(final int pRenderLayerIndex,
																			final FragmentedMemoryInterface pVolumeDataBuffer)
	{
		synchronized (getSetVolumeDataBufferLock(pRenderLayerIndex))
		{

			FragmentedMemoryInterface lVolumeDataBuffer = pVolumeDataBuffer;
			if (lVolumeDataBuffer == null)
				lVolumeDataBuffer = getVolumeDataBuffer(pRenderLayerIndex);
			if (lVolumeDataBuffer == null)
				return;

			final long lWidth = getVolumeSizeX();
			final long lHeight = getVolumeSizeY();
			final long lDepth = getVolumeSizeZ();

			mVolumeArrayWidth.setSingleInt((int) lWidth);
			mVolumeArrayHeight.setSingleInt((int) lHeight);
			mVolumeArrayDepth.setSingleInt((int) lDepth);

			final long lSizeInBytes = lVolumeDataBuffer.getSizeInBytes();

			final boolean lConsistent = lSizeInBytes == getBytesPerVoxel() * lWidth
																									* lHeight
																									* lDepth;
			if (!lConsistent)
				throw new RuntimeException("Buffer size not consistent with sizes!");
			assert (lConsistent);

			assert (mVolumeDataCudaArrays[pRenderLayerIndex] == null);
			mVolumeDataCudaArrays[pRenderLayerIndex] = new CudaArray(	1,
																																lWidth,
																																lHeight,
																																lDepth,
																																Size.of(getNativeType()),
																																false,
																																false,
																																false);

			assert (lVolumeDataBuffer.getSizeInBytes() == mVolumeDataCudaArrays[pRenderLayerIndex].getSizeInBytes());

			copyVolumeData(pRenderLayerIndex, lVolumeDataBuffer);

		}
	}

	private void copyVolumeData(final int pRenderLayerIndex,
															FragmentedMemoryInterface lVolumeDataBuffer)
	{
		synchronized (getSetVolumeDataBufferLock(pRenderLayerIndex))
		{
			if (lVolumeDataBuffer.getNumberOfFragments() == 1)
			{
				final ContiguousMemoryInterface lContiguousMemoryInterface = lVolumeDataBuffer.get(0);

				mVolumeDataCudaArrays[pRenderLayerIndex].copyFrom(lContiguousMemoryInterface,
																													true);
			}
			else
			{
				throw new RuntimeException("ClearCuda renderer does not support Fragmented memory yet.");
			}
		}
	}

	private void prepareVolumeDataTexture()
	{
		mVolumeDataCudaTexture = mCudaModule.getTexture("tex");
		mVolumeDataCudaTexture.setFilterMode(CUfilter_mode.CU_TR_FILTER_MODE_LINEAR);
		mVolumeDataCudaTexture.setAddressMode(0,
																					CUaddress_mode.CU_TR_ADDRESS_MODE_CLAMP);
		mVolumeDataCudaTexture.setAddressMode(1,
																					CUaddress_mode.CU_TR_ADDRESS_MODE_CLAMP);
		mVolumeDataCudaTexture.setFlags(jcuda.driver.JCudaDriver.CU_TRSF_NORMALIZED_COORDINATES);
	}

	private void pointTextureToArray(final int pRenderLayerIndex)
	{
		mVolumeDataCudaTexture.setTo(mVolumeDataCudaArrays[pRenderLayerIndex]);
		// mCurrentRenderKernel.setTexture(mVolumeDataCudaTexture);
	}

	/**
	 * Allocates CUDA array for transfer function, configures texture
	 */
	private void prepareTransferFunctionArray(final int pRenderLayerIndex)
	{
		final float[] lTransferFunctionArray = getTransferFunction(pRenderLayerIndex).getArray();
		final int lTransferFunctionArrayLength = lTransferFunctionArray.length;

		assert (mTransferFunctionCudaArrays[pRenderLayerIndex] == null);
		mTransferFunctionCudaArrays[pRenderLayerIndex] = new CudaArray(	4,
																																		lTransferFunctionArrayLength / 4,
																																		1,
																																		1,
																																		4,
																																		true,
																																		false,
																																		false);

		mTransferFunctionCudaArrays[pRenderLayerIndex].copyFrom(lTransferFunctionArray,
																														true);

	}

	/**
	 * Copies transfer function data.
	 */
	private void copyTransferFunctionArray(final int pRenderLayerIndex)
	{
		final float[] lTransferFunctionArray = getTransferFunction(pRenderLayerIndex).getArray();

		mTransferFunctionCudaArrays[pRenderLayerIndex].copyFrom(lTransferFunctionArray,
																														true);

	}

	private void prepareTransferFunctionTexture()
	{
		mTransferFunctionTexture = mCudaModule.getTexture("transferTex");

		mTransferFunctionTexture.setFilterMode(CUfilter_mode.CU_TR_FILTER_MODE_LINEAR);
		mTransferFunctionTexture.setAddressMode(0,
																						CUaddress_mode.CU_TR_ADDRESS_MODE_CLAMP);
		mTransferFunctionTexture.setAddressMode(1,
																						CUaddress_mode.CU_TR_ADDRESS_MODE_CLAMP);
		mTransferFunctionTexture.setFlags(jcuda.driver.JCudaDriver.CU_TRSF_NORMALIZED_COORDINATES);

	}

	private void pointTransferFunctionTextureToArray(final int pRenderLayerIndex)
	{
		mSizeOfTransferFunction.setSingleFloat(getTransferFunction(pRenderLayerIndex).getArray().length);
		mTransferFunctionTexture.setTo(mTransferFunctionCudaArrays[pRenderLayerIndex]);
		mCurrentRenderKernel.setTexture(mTransferFunctionTexture);
	}

	@Override
	public void dispose(final GLAutoDrawable pArg0)
	{
		printMemoryState();
		disposeVolumeRenderer();
		super.dispose(pArg0);
	}

	private void disposeVolumeRenderer()
	{
		waitToFinishAllDataBufferCopy(1, TimeUnit.SECONDS);

		mDisplayReentrantLock.lock();
		try
		{

			try
			{
				for (int i = 0; i < getNumberOfRenderLayers(); i++)

					mInvertedModelViewMatrix.close();
				mInvertedProjectionMatrix.close();
				mSizeOfTransferFunction.close();
			}
			catch (final Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(	"Exception while closing " + this.getClass()
																																			.getSimpleName(),
																		e);
			}

			try
			{
				for (int i = 0; i < getNumberOfRenderLayers(); i++)
				{
					synchronized (getSetVolumeDataBufferLock(i))
					{
						if (mVolumeDataCudaArrays[i] != null)
						{
							System.out.println("closing VolumeDataCudaArrays !");
							mVolumeDataCudaArrays[i].close();
							mVolumeDataCudaArrays[i] = null;
						}
					}
				}
			}
			catch (final Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(	"Exception while closing " + this.getClass()
																																			.getSimpleName(),
																		e);
			}

			try
			{
				for (int i = 0; i < getNumberOfRenderLayers(); i++)
				{
					if (mTransferFunctionCudaArrays[i] != null)
					{
						System.out.println("closing TransferFunctionCudaArrays !");
						mTransferFunctionCudaArrays[i].close();
						mTransferFunctionCudaArrays[i] = null;
					}
				}
			}
			catch (final Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(	"Exception while closing " + this.getClass()
																																			.getSimpleName(),
																		e);
			}

			try
			{
				if (mCudaModule != null)
				{
					System.out.println("closing CudaModule !");
					mCudaModule.close();
					mCudaModule = null;
				}
			}
			catch (final Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(	"Exception while closing " + this.getClass()
																																			.getSimpleName(),
																		e);
			}

			try
			{
				if (mCudaContext != null)
				{
					System.out.println("closing CudaContext !");
					mCudaContext.close();
					mCudaContext = null;
				}
			}
			catch (final Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(	"Exception while closing " + this.getClass()
																																			.getSimpleName(),
																		e);
			}

			try
			{
				if (mCudaDevice != null)
				{
					printMemoryState();
					mCudaDevice.close();
					mCudaDevice = null;
				}

			}
			catch (final Throwable e)
			{
				e.printStackTrace();
				throw new RuntimeException(	"Exception while closing " + this.getClass()
																																			.getSimpleName(),
																		e);
			}

		}
		finally
		{
			if (mDisplayReentrantLock.isHeldByCurrentThread())
				mDisplayReentrantLock.unlock();
		}
	}

	/**
	 * Integral division, rounding the result to the next highest integer.
	 *
	 * @param a
	 *          Dividend
	 * @param b
	 *          Divisor
	 * @return a/b rounded to the next highest integer.
	 */
	private static int iDivUp(final int a, final int b)
	{
		return (a % b != 0) ? (a / b + 1) : (a / b);
	}

	/**
	 * Interface method implementation
	 *
	 * @see clearvolume.renderer.cleargl.JOGLClearVolumeFrameRenderer#renderVolume(float[],
	 *      float[])
	 */
	@Override
	protected boolean[] renderVolume(	final float[] pInverseModelViewMatrix,
																		final float[] pInverseProjectionMatrix)
	{
		if (mCudaContext == null)
			return null;
		mCudaContext.setCurrent();

		doCaptureBuffersIfNeeded();

		try
		{
			mInvertedModelViewMatrix.copyFrom(pInverseModelViewMatrix, true);
			mInvertedProjectionMatrix.copyFrom(	pInverseProjectionMatrix,
																					true);
			return updateBufferAndRunKernel();
		}
		catch (final CudaException e)
		{
			e.printStackTrace();
			return null;
		}
	}

	private void doCaptureBuffersIfNeeded()
	{
		if (mVolumeCaptureFlag)
		{
			final ByteBuffer[] lCaptureBuffers = new ByteBuffer[getNumberOfRenderLayers()];

			for (int i = 0; i < getNumberOfRenderLayers(); i++)
			{
				synchronized (getSetVolumeDataBufferLock(i))
				{
					lCaptureBuffers[i] = ByteBuffer.allocateDirect((int) (Size.of(getNativeType()) * getVolumeSizeX()
																																* getVolumeSizeY() * getVolumeSizeZ()))
																					.order(ByteOrder.nativeOrder());

					mVolumeDataCudaArrays[getCurrentRenderLayerIndex()].copyTo(	lCaptureBuffers[i],
																																			true);
				}
			}

			notifyVolumeCaptureListeners(	lCaptureBuffers,
																		getNativeType(),
																		getVolumeSizeX(),
																		getVolumeSizeY(),
																		getVolumeSizeZ(),
																		getVoxelSizeX(),
																		getVoxelSizeY(),
																		getVoxelSizeZ());

			mVolumeCaptureFlag = false;
		}
	}

	/**
	 * Call the kernel function, rendering the 3D volume data image
	 *
	 * @return boolean array indicating which layer was updated.
	 */
	boolean[] updateBufferAndRunKernel()
	{
		final boolean[] lUpdatedLayer = new boolean[getNumberOfRenderLayers()];

		boolean lAnyVolumeDataUpdated = false;

		for (int lRenderLayerIndex = 0; lRenderLayerIndex < getNumberOfRenderLayers(); lRenderLayerIndex++)
		{
			synchronized (getSetVolumeDataBufferLock(lRenderLayerIndex))
			{
				final FragmentedMemoryInterface lVolumeDataBuffer = getVolumeDataBuffer(lRenderLayerIndex);

				if (lVolumeDataBuffer != null)
				{

					clearVolumeDataBufferReference(lRenderLayerIndex);

					if (haveVolumeDimensionsChanged() || mVolumeDataCudaArrays[lRenderLayerIndex] == null)
					{
						if (mVolumeDataCudaArrays[lRenderLayerIndex] != null)
						{
							mVolumeDataCudaArrays[lRenderLayerIndex].close();
							mVolumeDataCudaArrays[lRenderLayerIndex] = null;
						}

						prepareVolumeDataArray(	lRenderLayerIndex,
																		lVolumeDataBuffer);

						clearVolumeDimensionsChanged();
					}
					else
					{
						assert (lVolumeDataBuffer.getSizeInBytes() == mVolumeDataCudaArrays[lRenderLayerIndex].getSizeInBytes());
						copyVolumeData(lRenderLayerIndex, lVolumeDataBuffer);
					}

					notifyCompletionOfDataBufferCopy(lRenderLayerIndex);
					lAnyVolumeDataUpdated |= true;

					runProcessorsHook(lRenderLayerIndex);
				}

			}
		}

		final long startTime = System.nanoTime();

		if (lAnyVolumeDataUpdated || haveVolumeRenderingParametersChanged()
				|| getAdaptiveLODController().isKernelRunNeeded())
			for (int i = 0; i < getNumberOfRenderLayers(); i++)
			{
				if (mVolumeDataCudaArrays[i] != null)
				{
					runKernel(i);
					lUpdatedLayer[i] = true;
				}
			}

		final long endTime = System.nanoTime();

		/*System.out.println("time to render: " + (endTime - startTime)
												/ 1000000.
												+ " ms");/**/

		return lUpdatedLayer;
	}

	/**
	 * Runs 3D to 2D rendering kernel.
	 */
	private void runKernel(final int pRenderLayerIndex)
	{

		final CudaDevicePointer lCudaDevicePointer = mCudaBufferDevicePointer[pRenderLayerIndex];

		if (isLayerVisible(pRenderLayerIndex))
		{
			// synchronized (getSetVolumeDataBufferLock(pRenderLayerIndex))
			{
				switch (getRenderAlgorithm(pRenderLayerIndex))
				{
				case MaxProjection:
					mCurrentRenderKernel = mMaxProjectionRenderKernel;
					break;
				case IsoSurface:
					mCurrentRenderKernel = mIsoSurfaceRenderKernel;
					break;
				}

				copyTransferFunctionArray(pRenderLayerIndex);

				pointTransferFunctionTextureToArray(pRenderLayerIndex);
				pointTextureToArray(pRenderLayerIndex);

				mCurrentRenderKernel.setGridDim(iDivUp(	getRenderWidth(),
																								cBlockSize),
																				iDivUp(	getRenderHeight(),
																								cBlockSize),
																				1);

				mCurrentRenderKernel.setBlockDim(cBlockSize, cBlockSize, 1);

				final int lMaxNumberSteps = getMaxSteps(pRenderLayerIndex);
				final int lNumberOfPasses = getAdaptiveLODController().getNumberOfPasses();

				final int lPassIndex = getAdaptiveLODController().getPassIndex();
				final boolean lActive = getAdaptiveLODController().isActive();

				int lMaxSteps = lMaxNumberSteps;
				float lDithering = 0;
				float lPhase = 0;
				int lClear = 0;

				switch (getRenderAlgorithm(pRenderLayerIndex))
				{
				case MaxProjection:
					lMaxSteps = max(16, lMaxNumberSteps / lNumberOfPasses);
					lDithering = getDithering(pRenderLayerIndex) * (1.0f * (lNumberOfPasses - lPassIndex) / lNumberOfPasses);
					lPhase = getAdaptiveLODController().getPhase();
					lClear = (lPassIndex == 0) ? 0 : 1;

					mCurrentRenderKernel.launch(lCudaDevicePointer,
																			getRenderWidth(),
																			getRenderHeight(),
																			(float) getBrightness(pRenderLayerIndex),
																			(float) getTransferRangeMin(pRenderLayerIndex),
																			(float) getTransferRangeMax(pRenderLayerIndex),
																			(float) getGamma(pRenderLayerIndex),
																			lMaxSteps,
																			lDithering,
																			lPhase,
																			lClear);

					break;
				case IsoSurface:
					lMaxSteps = max(16,
													(lMaxNumberSteps * (1 + lPassIndex)) / (2 * lNumberOfPasses));
					lDithering = (float) pow(	getDithering(pRenderLayerIndex) * (1.0f * (lNumberOfPasses - lPassIndex) / lNumberOfPasses),
																		2);
					lPhase = getAdaptiveLODController().getPhase();
					lClear = (lPassIndex == lNumberOfPasses - 1) || (lPassIndex == 0)	? 0
																																						: 1;

					final float[] lLightVector = getLightVector();

					mCurrentRenderKernel.launch(lCudaDevicePointer,
																			getRenderWidth(),
																			getRenderHeight(),
																			(float) getBrightness(pRenderLayerIndex),
																			(float) getTransferRangeMin(pRenderLayerIndex),
																			(float) getTransferRangeMax(pRenderLayerIndex),
																			(float) getGamma(pRenderLayerIndex),
																			lLightVector[0],
																			lLightVector[1],
																			lLightVector[2],
																			lMaxSteps,
																			lDithering,
																			lPhase,
																			lClear);

					break;
				}

				/*System.out.format("### steps=%d, dith=%g, phase=%g, clear=%d \n",
													lMaxSteps,
													lDithering,
													lPhase,
													lClear);/**/

			}
		}
		else
			lCudaDevicePointer.fillByte((byte) 0, false);

		if (mTemporaryTransfertBuffer == null || mTemporaryTransfertBuffer.capacity() != lCudaDevicePointer.getSizeInBytes())
			mTemporaryTransfertBuffer = ByteBuffer.allocateDirect((int) lCudaDevicePointer.getSizeInBytes())
																						.order(ByteOrder.nativeOrder());

		mTemporaryTransfertBuffer.clear();

		mCudaBufferDevicePointer[pRenderLayerIndex].copyTo(	mTemporaryTransfertBuffer,
																												true);

		mTemporaryTransfertBuffer.rewind();
		copyBufferToTexture(pRenderLayerIndex, mTemporaryTransfertBuffer);
	}

	private void runProcessorsHook(final int pRenderLayerIndex)
	{
		for (final Processor<?> lProcessor : mProcessorsMap.values())
			if (lProcessor.isCompatibleProcessor(getClass()))
			{
				synchronized (getSetVolumeDataBufferLock(pRenderLayerIndex))
				{
					if (lProcessor instanceof CUDAProcessor)
					{

						final CUDAProcessor<?> lCUDAProcessor = (CUDAProcessor<?>) lProcessor;
						lCUDAProcessor.applyToArray(mVolumeDataCudaArrays[pRenderLayerIndex]);

					}
					lProcessor.process(	pRenderLayerIndex,
															getVolumeSizeX(),
															getVolumeSizeY(),
															getVolumeSizeZ());
				}
			}
	}

	/* (non-Javadoc)
	 * @see clearvolume.renderer.cleargl.ClearGLVolumeRenderer#close()
	 */
	@Override
	public void close()
	{
		super.close();
	}

	@Override
	public long getMax3DBufferSize()
	{
		return mCudaDevice.getAvailableMem();
	}

	@Override
	public long getMaxVolumeWidth()
	{
		return mCudaDevice.getMaxTexture3DWidth();
	}

	@Override
	public long getMaxVolumeHeight()
	{
		return mCudaDevice.getMaxTexture3DHeight();
	}

	@Override
	public long getMaxVolumeDepth()
	{
		return mCudaDevice.getMaxTexture3DDepth();
	}
}