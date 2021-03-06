package clearvolume.renderer.cleargl.overlay.o2d;

import static java.lang.Math.max;
import static java.lang.Math.min;
import gnu.trove.list.linked.TFloatLinkedList;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import cleargl.ClearGeometryObject;
import cleargl.GLError;
import cleargl.GLFloatArray;
import cleargl.GLIntArray;
import cleargl.GLMatrix;
import cleargl.GLProgram;
import clearvolume.audio.audioplot.AudioPlot;
import clearvolume.renderer.DisplayRequestInterface;
import clearvolume.renderer.SingleKeyToggable;
import clearvolume.renderer.cleargl.overlay.Overlay2D;
import clearvolume.renderer.cleargl.overlay.OverlayBase;
import clearvolume.renderer.processors.ProcessorInterface;
import clearvolume.renderer.processors.ProcessorResultListener;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.opengl.GL;

public class PlainGraphOverlay extends OverlayBase implements
																									Overlay2D,
																									SingleKeyToggable,
																									ProcessorResultListener<Double>,
																									AutoCloseable
{

	private static final int cMaximalWaitTimeForLockInMilliseconds = 10;

	// seems to be supported

	private GLProgram mGLProgram;
	private ClearGeometryObject mClearGeometryObject;
	private GLFloatArray mVerticesFloatArray;
	private GLIntArray mIndexIntArray;
	private GLFloatArray mNormalArray;
	private GLFloatArray mTexCoordFloatArray;

	private final TFloatLinkedList mDataY = new TFloatLinkedList();

	private final ReentrantLock mReentrantLock = new ReentrantLock();

	private int mMaxNumberOfDataPoints;

	private DisplayRequestInterface mDisplayRequestInterface;
	private volatile boolean mHasChanged = false;

	private volatile float mOffsetX = -1, mOffsetY = 2f / 3;
	private volatile float mScaleX = 1, mScaleY = 1f / 3;
	private volatile float mMin;
	private volatile float mMax;
	private final float mAlpha = 0.04f;

	private final AudioPlot mAudioPlot = new AudioPlot();

	protected volatile boolean mStopSignal = false;

	public PlainGraphOverlay(int pMaxNumberOfDataPoints)
	{
		super();
		setMaxNumberOfDataPoints(pMaxNumberOfDataPoints);

		clearMinMax();
		final Runnable lRunnable = new Runnable()
		{

			@Override
			public void run()
			{
				while (!mStopSignal)
				{
					if (isDisplayed())
						computeMinMax(mAlpha);
					if (mDisplayRequestInterface != null)
						mDisplayRequestInterface.requestDisplay();
					try
					{
						Thread.sleep(50);
					}
					catch (final InterruptedException e)
					{
					}

				}

			}
		};

		final Thread lMinMaxCalculationThread = new Thread(	lRunnable,
																												PlainGraphOverlay.class.getSimpleName() + ".MinMaxCalculationThread");
		lMinMaxCalculationThread.setDaemon(true);
		lMinMaxCalculationThread.setPriority(Thread.MIN_PRIORITY);
		lMinMaxCalculationThread.start();

		mAudioPlot.setInvertRange(false);
	}

	@Override
	public boolean toggle()
	{
		final boolean lNewState = super.toggle();
		if (lNewState)
			mAudioPlot.start();
		else
			mAudioPlot.stop();
		return lNewState;
	}

	@Override
	public String getName()
	{
		return "graph";
	}

	@Override
	public short toggleKeyCode()
	{
		return KeyEvent.VK_G;
	}

	@Override
	public int toggleKeyModifierMask()
	{
		return 0;
	}

	@Override
	public boolean hasChanged2D()
	{
		return mHasChanged;
	}

	public int getMaxNumberOfDataPoints()
	{
		return mMaxNumberOfDataPoints;
	}

	public void setMaxNumberOfDataPoints(int pMaxNumberOfDataPoints)
	{
		mMaxNumberOfDataPoints = pMaxNumberOfDataPoints;
	}

	@Override
	public void notifyResult(ProcessorInterface<Double> pSource, Double pResult)
	{
		addPoint(pResult);
	}

	public void addPoint(double pY)
	{
		mAudioPlot.setValue(normalizeAndClamp((float) pY));

		mReentrantLock.lock();
		try
		{
			mDataY.add((float) pY);
			if (mDataY.size() > getMaxNumberOfDataPoints())
				mDataY.removeAt(0);
			if (mDataY.size() < 20)
				computeMinMax(0.5f);
			mHasChanged = true;
		}
		finally
		{
			if (mReentrantLock.isHeldByCurrentThread())
				mReentrantLock.unlock();
		}

	}

	public void clear()
	{
		mReentrantLock.lock();
		try
		{
			mDataY.clear();
			clearMinMax();
			mHasChanged = true;
		}
		finally
		{
			if (mReentrantLock.isHeldByCurrentThread())
				mReentrantLock.unlock();
		}

	}

	private void computeMinMax(float pAlpha)
	{
		try
		{
			final boolean lIsLocked = mReentrantLock.tryLock(	0,
																												TimeUnit.MILLISECONDS);

			if (lIsLocked)
			{
				if (mDataY.size() == 0)
					return;

				final float lMin = mDataY.min();
				final float lMax = mDataY.max();

				if (lMin < mMin)
					mMin = pAlpha * lMin + (1 - pAlpha) * mMin;

				if (lMax > mMax)
					mMax = pAlpha * lMax + (1 - pAlpha) * mMax;

			}
		}
		catch (final InterruptedException e)
		{
		}
		finally
		{
			if (mReentrantLock.isHeldByCurrentThread())
				mReentrantLock.unlock();
		}

	}

	private void clearMinMax()
	{
		try
		{
			final boolean lIsLocked = mReentrantLock.tryLock(	0,
																												TimeUnit.MILLISECONDS);
			if (lIsLocked)
			{
				mMin = 0;
				mMax = 0;
			}
		}
		catch (final InterruptedException e)
		{
		}
		finally
		{
			if (mReentrantLock.isHeldByCurrentThread())
				mReentrantLock.unlock();
		}

	}

	@Override
	public void init(	GL pGL,
										DisplayRequestInterface pDisplayRequestInterface)
	{
		mAudioPlot.start();

		mDisplayRequestInterface = pDisplayRequestInterface;
		// box display: construct the program and related objects
		mReentrantLock.lock();
		try
		{
			mGLProgram = GLProgram.buildProgram(pGL,
																					PlainGraphOverlay.class,
																					"shaders/graph_vert.glsl",
																					"shaders/graph_frag.glsl");

			mClearGeometryObject = new ClearGeometryObject(	mGLProgram,
																											3,
																											GL.GL_TRIANGLE_STRIP);
			mClearGeometryObject.setDynamic(true);

			final int lNumberOfPointsToDraw = 2 * getMaxNumberOfDataPoints();

			mVerticesFloatArray = new GLFloatArray(lNumberOfPointsToDraw, 3);
			mNormalArray = new GLFloatArray(lNumberOfPointsToDraw, 3);
			mIndexIntArray = new GLIntArray(lNumberOfPointsToDraw, 1);
			mTexCoordFloatArray = new GLFloatArray(lNumberOfPointsToDraw, 2);

			mVerticesFloatArray.fillZeros();
			mNormalArray.fillZeros();
			mIndexIntArray.fillZeros();
			mTexCoordFloatArray.fillZeros();

			mClearGeometryObject.setVerticesAndCreateBuffer(mVerticesFloatArray.getFloatBuffer());
			mClearGeometryObject.setNormalsAndCreateBuffer(mNormalArray.getFloatBuffer());
			mClearGeometryObject.setTextureCoordsAndCreateBuffer(mTexCoordFloatArray.getFloatBuffer());
			mClearGeometryObject.setIndicesAndCreateBuffer(mIndexIntArray.getIntBuffer());

			GLError.printGLErrors(pGL, "AFTER GRAPH OVERLAY INIT");

		}
		catch (final IOException e)
		{
			e.printStackTrace();
		}
		finally
		{
			if (mReentrantLock.isHeldByCurrentThread())
				mReentrantLock.unlock();
		}
	}

	private final float transformX(float pX)
	{
		return mOffsetX + mScaleX * pX;
	}

	private final float transformY(float pY)
	{
		return mOffsetY + mScaleY * pY;
	}

	@Override
	public void render2D(	GL pGL,
												int pWidth,
												int pHeight,
												GLMatrix pProjectionMatrix)
	{
		if (isDisplayed())
		{
			try
			{
				mReentrantLock.lock();
				{

					// System.out.format("________________________________________\n");
					// System.out.println(mDataY.size());

					mIndexIntArray.clear();
					mVerticesFloatArray.clear();
					mTexCoordFloatArray.clear();

					final float lStepX = 1f / mDataY.size();
					int i = 0;
					for (i = 0; i < mDataY.size(); i++)
					{
						final float lNormalizedValue = normalizeAndClamp(mDataY.get(i));

						final float x = transformX(i * lStepX);
						final float y = transformY(lNormalizedValue);

						mVerticesFloatArray.add(x, transformY(0), -10);
						mTexCoordFloatArray.add(x, 0);
						mIndexIntArray.add(2 * i);
						mVerticesFloatArray.add(x, y, -10);
						mTexCoordFloatArray.add(x, 1);
						mIndexIntArray.add(2 * i + 1);
						// System.out.format("%g\t%g\n", x, y);
						// System.out.println(y);
					}

					/*mVerticesFloatArray.add(transformX(1), transformY(0), -10);
					mIndexIntArray.add(i++);
					mVerticesFloatArray.add(transformX(0), transformY(0), -10);
					mIndexIntArray.add(i++);/**/

					mVerticesFloatArray.padZeros();
					mTexCoordFloatArray.padZeros();
					mIndexIntArray.padZeros();

					/*System.out.println("mVerticesFloatArray.getFloatBuffer().limit()=" + mVerticesFloatArray.getFloatBuffer()
																																																	.limit());
					System.out.println("mTexCoordFloatArray.getFloatBuffer().limit()=" + mTexCoordFloatArray.getFloatBuffer()
																																																	.limit());
					System.out.println("mIndexIntArray.getFloatBuffer().limit()=" + mIndexIntArray.getIntBuffer()
																																												.limit());/**/

					mClearGeometryObject.updateVertices(mVerticesFloatArray.getFloatBuffer());
					GLError.printGLErrors(pGL,
																"AFTER mClearGeometryObject.updateVertices");
					mClearGeometryObject.updateTextureCoords(mTexCoordFloatArray.getFloatBuffer());
					GLError.printGLErrors(pGL,
																"AFTER mClearGeometryObject.updateTextureCoords");
					mClearGeometryObject.updateIndices(mIndexIntArray.getIntBuffer());
					GLError.printGLErrors(pGL,
																"AFTER mClearGeometryObject.updateIndices");

					// mGLProgram.use(pGL);
					mClearGeometryObject.setProjection(pProjectionMatrix);

					// System.out.println(pProjectionMatrix.toString());

					pGL.glDisable(GL.GL_DEPTH_TEST);
					pGL.glEnable(GL.GL_BLEND);
					pGL.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
					pGL.glBlendEquation(GL.GL_FUNC_ADD);/**/

					mClearGeometryObject.draw(0, mDataY.size() * 2);

					mHasChanged = false;
				}

			}
			finally
			{
				if (mReentrantLock.isHeldByCurrentThread())
					mReentrantLock.unlock();
			}
		}
	}

	private float normalizeAndClamp(float pValue)
	{
		if (mMax == mMin)
			return 0;
		float lValue = (pValue - mMin) / (mMax - mMin);
		lValue = min(max(lValue, 0), 1);
		return lValue;
	}

	@Override
	public void close()
	{
		mAudioPlot.close();
		mStopSignal = true;
	}

}
