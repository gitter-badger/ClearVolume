package clearvolume.renderer;

public class AdaptiveLODController
{

	private static final long cMarginTime = 1000 * 1000 * 100; // 10 ms
	private static final int cMaxRenderStepsPerPass = 128;

	private final int[] cFibonacci = new int[]
	{ 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144 };

	private volatile boolean mMultiPassRenderingInProgress;
	private volatile boolean mRenderingParametersOrVolumeDataChanged = true;

	private volatile int mFibonacciPassNumber;
	private volatile int mGenerator;
	private volatile int mPassIndex;
	private volatile int mCurrentMaxNumberOfSteps;

	private volatile long mLastUserInputTime = Long.MIN_VALUE;

	public AdaptiveLODController()
	{
		setFibonacciPassNumber(6);
		resetMultiPassRendering();
	}

	private void setFibonacciPassNumber(final int pFibonacciPassNumber)
	{
		mFibonacciPassNumber = pFibonacciPassNumber;
		mGenerator = cFibonacci[mFibonacciPassNumber - 1];
	}

	private void setFibonacciPassNumberFromCurrentMaxNumberOfSteps()
	{

		final int lIdeaNumberOfPasses = Math.round(((float) mCurrentMaxNumberOfSteps) / cMaxRenderStepsPerPass);

		for (int i = 1; i < cFibonacci.length; i++)
		{
			if (cFibonacci[i] > lIdeaNumberOfPasses)
			{
				setFibonacciPassNumber(i);
				break;
			}
		}

	}

	public boolean isKernelRunNeeded()
	{
		return mMultiPassRenderingInProgress;
	}

	public float getPhase()
	{
		final float lPhase = computePhase(getNumberOfPasses(),
																			mGenerator,
																			mPassIndex);
		println("lPhase=" + lPhase);
		return lPhase;
	}

	public int getNumberOfPasses()
	{
		return cFibonacci[mFibonacciPassNumber];
	}

	public boolean isBufferClearingNeeded()
	{
		return mPassIndex == 0;
	}

	public boolean isRedrawNeeded()
	{
		return mMultiPassRenderingInProgress;
	}

	public void renderingParametersOrVolumeDataChanged()
	{
		println(this.getClass().getSimpleName() + ".renderingParametersOrVolumeDataChanged");
		mMultiPassRenderingInProgress = true;
	}

	private void resetMultiPassRendering()
	{
		mPassIndex = 0;
	}

	public void notifyMaxNumberOfSteps(int pMaxNumberSteps)
	{
		mCurrentMaxNumberOfSteps = pMaxNumberSteps;
	}

	public void beforeRendering()
	{
		println(this.getClass().getSimpleName() + ".beforeRendering");
		if (mMultiPassRenderingInProgress)
		{
			println(this.getClass().getSimpleName() + ".beforeRendering -> multi-pass is active");
			if (isUserInteractionInProgress())
			{
				// multipass rendering needs to restart from scratch:
				println(this.getClass().getSimpleName() + ".beforeRendering -> multi-pass needs to be restarted");
				resetMultiPassRendering();

			}
			else
			{
				proceedWithMultiPass();
			}
		}
		else
		{
			println(this.getClass().getSimpleName() + ".beforeRendering -> multi-pass not active");
		}
	}

	public void afterRendering()
	{

	}

	private void proceedWithMultiPass()
	{
		// multi-pass continues:
		println(this.getClass().getSimpleName() + ".proceedWithMultiPass -> continues with pass #"
						+ mPassIndex);
		mPassIndex++;
		if (mPassIndex < getNumberOfPasses())
		{
			// still need torender more passes:
			println(this.getClass().getSimpleName() + ".proceedWithMultiPass -> more passes to do");
			// triggerDeamonThreadToRequestRender();
		}
		else
		{
			// we are done:
			println(this.getClass().getSimpleName() + ".proceedWithMultiPass -> all passes done! finished!");
			mMultiPassRenderingInProgress = false;
			setFibonacciPassNumberFromCurrentMaxNumberOfSteps();
			resetMultiPassRendering();
		}
	}

	private static float computePhase(int pNumberOfPasses,
																		int pGenerator,
																		int pPassIndex)
	{
		final float lPhase = (((float) (pPassIndex * pGenerator) % pNumberOfPasses)) / pNumberOfPasses;
		return lPhase;
	}

	public void notifyUserInteractionInProgress()
	{
		mLastUserInputTime = System.nanoTime();
	}

	public void notifyUserInteractionEnded()
	{
		mLastUserInputTime = Long.MIN_VALUE;
	}

	public boolean isUserInteractionInProgress()
	{
		if (System.nanoTime() > mLastUserInputTime + cMarginTime)
			return false;
		return true;
	}

	private void println(String pString)
	{
		// System.out.println(pString);
	}

}
