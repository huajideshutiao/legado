/* Sonic library
   Copyright 2010, 2011
   Bill Cox
   This file is part of the Sonic Library.

   This file is licensed under the Apache 2.0 license.

   Vendored from github.com/waywardgeek/sonic (Apache-2.0), converted to Kotlin.
   逐方法等价转换, 未做算法改动。
*/

package io.legado.desktop.audio

@Suppress("MemberVisibilityCanBePrivate", "unused", "NAME_SHADOWING")
class Sonic(initialSampleRate: Int, initialNumChannels: Int) {

    private var inputBuffer: ShortArray = ShortArray(0)
    private var outputBuffer: ShortArray = ShortArray(0)
    private var pitchBuffer: ShortArray = ShortArray(0)
    private var downSampleBuffer: ShortArray = ShortArray(0)
    private var speed = 0f
    private var volume = 0f
    private var pitch = 0f
    private var rate = 0f
    private var oldRatePosition = 0
    private var newRatePosition = 0
    private var useChordPitch = false
    private var quality = 0
    private var numChannels = 0
    private var inputBufferSize = 0
    private var pitchBufferSize = 0
    private var outputBufferSize = 0
    private var numInputSamples = 0
    private var numOutputSamples = 0
    private var numPitchSamples = 0
    private var minPeriod = 0
    private var maxPeriod = 0
    private var maxRequired = 0
    private var remainingInputToCopy = 0
    private var sampleRate = 0
    private var prevPeriod = 0
    private var prevMinDiff = 0
    private var minDiff = 0
    private var maxDiff = 0

    // Create a sonic stream.
    init {
        allocateStreamBuffers(initialSampleRate, initialNumChannels)
        speed = 1.0f
        pitch = 1.0f
        volume = 1.0f
        rate = 1.0f
        oldRatePosition = 0
        newRatePosition = 0
        useChordPitch = false
        quality = 0
    }

    // Resize the array.
    private fun resize(oldArray: ShortArray, newLength: Int): ShortArray {
        val newLen = newLength * numChannels
        val newArray = ShortArray(newLen)
        val length = if (oldArray.size <= newLen) oldArray.size else newLen

        System.arraycopy(oldArray, 0, newArray, 0, length)
        return newArray
    }

    // Move samples from one array to another.  May move samples down within an array, but not up.
    private fun move(
        dest: ShortArray,
        destPos: Int,
        source: ShortArray,
        sourcePos: Int,
        numSamples: Int
    ) {
        System.arraycopy(
            source, sourcePos * numChannels, dest, destPos * numChannels, numSamples * numChannels
        )
    }

    // Scale the samples by the factor.
    private fun scaleSamples(
        samples: ShortArray,
        position: Int,
        numSamples: Int,
        volume: Float
    ) {
        // Convert volume to fixed-point, with a 12 bit fraction.
        val fixedPointVolume = (volume * 4096.0f).toInt()
        val start = position * numChannels
        val stop = start + numSamples * numChannels

        for (xSample in start until stop) {
            // Convert back from fixed point to 16-bit integer.
            var value = (samples[xSample] * fixedPointVolume) shr 12
            if (value > 32767) {
                value = 32767
            } else if (value < -32767) {
                value = -32767
            }
            samples[xSample] = value.toShort()
        }
    }

    // Get the speed of the stream.
    fun getSpeed(): Float = speed

    // Set the speed of the stream.
    fun setSpeed(speed: Float) {
        this.speed = speed
    }

    // Get the pitch of the stream.
    fun getPitch(): Float = pitch

    // Set the pitch of the stream.
    fun setPitch(pitch: Float) {
        this.pitch = pitch
    }

    // Get the rate of the stream.
    fun getRate(): Float = rate

    // Set the playback rate of the stream. This scales pitch and speed at the same time.
    fun setRate(rate: Float) {
        this.rate = rate
        this.oldRatePosition = 0
        this.newRatePosition = 0
    }

    // Get the vocal chord pitch setting.
    fun getChordPitch(): Boolean = useChordPitch

    // Set the vocal chord mode for pitch computation.  Default is off.
    fun setChordPitch(useChordPitch: Boolean) {
        this.useChordPitch = useChordPitch
    }

    // Get the quality setting.
    fun getQuality(): Int = quality

    // Set the "quality".  Default 0 is virtually as good as 1, but very much faster.
    fun setQuality(quality: Int) {
        this.quality = quality
    }

    // Get the scaling factor of the stream.
    fun getVolume(): Float = volume

    // Set the scaling factor of the stream.
    fun setVolume(volume: Float) {
        this.volume = volume
    }

    // Allocate stream buffers.
    private fun allocateStreamBuffers(sampleRate: Int, numChannels: Int) {
        minPeriod = sampleRate / SONIC_MAX_PITCH
        maxPeriod = sampleRate / SONIC_MIN_PITCH
        maxRequired = 2 * maxPeriod
        inputBufferSize = maxRequired
        inputBuffer = ShortArray(maxRequired * numChannels)
        outputBufferSize = maxRequired
        outputBuffer = ShortArray(maxRequired * numChannels)
        pitchBufferSize = maxRequired
        pitchBuffer = ShortArray(maxRequired * numChannels)
        downSampleBuffer = ShortArray(maxRequired)
        this.sampleRate = sampleRate
        this.numChannels = numChannels
        oldRatePosition = 0
        newRatePosition = 0
        prevPeriod = 0
    }

    // Get the sample rate of the stream.
    fun getSampleRate(): Int = sampleRate

    // Set the sample rate of the stream.  This will cause samples buffered in the stream to be lost.
    fun setSampleRate(sampleRate: Int) {
        allocateStreamBuffers(sampleRate, numChannels)
    }

    // Get the number of channels.
    fun getNumChannels(): Int = numChannels

    // Set the num channels of the stream.  This will cause samples buffered in the stream to be lost.
    fun setNumChannels(numChannels: Int) {
        allocateStreamBuffers(sampleRate, numChannels)
    }

    // Enlarge the output buffer if needed.
    private fun enlargeOutputBufferIfNeeded(numSamples: Int) {
        if (numOutputSamples + numSamples > outputBufferSize) {
            outputBufferSize += (outputBufferSize shr 1) + numSamples
            outputBuffer = resize(outputBuffer, outputBufferSize)
        }
    }

    // Enlarge the input buffer if needed.
    private fun enlargeInputBufferIfNeeded(numSamples: Int) {
        if (numInputSamples + numSamples > inputBufferSize) {
            inputBufferSize += (inputBufferSize shr 1) + numSamples
            inputBuffer = resize(inputBuffer, inputBufferSize)
        }
    }

    // Add the input samples to the input buffer.
    private fun addFloatSamplesToInputBuffer(samples: FloatArray, numSamples: Int) {
        if (numSamples == 0) {
            return
        }
        enlargeInputBufferIfNeeded(numSamples)
        var xBuffer = numInputSamples * numChannels
        for (xSample in 0 until numSamples * numChannels) {
            inputBuffer[xBuffer++] = (samples[xSample] * 32767.0f).toInt().toShort()
        }
        numInputSamples += numSamples
    }

    // Add the input samples to the input buffer.
    private fun addShortSamplesToInputBuffer(samples: ShortArray?, numSamples: Int) {
        if (numSamples == 0) {
            return
        }
        enlargeInputBufferIfNeeded(numSamples)
        move(inputBuffer, numInputSamples, samples!!, 0, numSamples)
        numInputSamples += numSamples
    }

    // Add the input samples to the input buffer.
    private fun addUnsignedByteSamplesToInputBuffer(samples: ByteArray, numSamples: Int) {
        var sample: Short

        enlargeInputBufferIfNeeded(numSamples)
        var xBuffer = numInputSamples * numChannels
        for (xSample in 0 until numSamples * numChannels) {
            // Convert from unsigned to signed
            sample = ((samples[xSample].toInt() and 0xff) - 128).toShort()
            inputBuffer[xBuffer++] = (sample.toInt() shl 8).toShort()
        }
        numInputSamples += numSamples
    }

    // Add the input samples to the input buffer.  They must be 16-bit little-endian encoded in a byte array.
    private fun addBytesToInputBuffer(inBuffer: ByteArray, numBytes: Int) {
        val numSamples = numBytes / (2 * numChannels)
        var sample: Short

        enlargeInputBufferIfNeeded(numSamples)
        var xBuffer = numInputSamples * numChannels
        var xByte = 0
        while (xByte + 1 < numBytes) {
            sample = ((inBuffer[xByte].toInt() and 0xff) or (inBuffer[xByte + 1].toInt() shl 8)).toShort()
            inputBuffer[xBuffer++] = sample
            xByte += 2
        }
        numInputSamples += numSamples
    }

    // Remove input samples that we have already processed.
    private fun removeInputSamples(position: Int) {
        val remainingSamples = numInputSamples - position

        move(inputBuffer, 0, inputBuffer, position, remainingSamples)
        numInputSamples = remainingSamples
    }

    // Just copy from the array to the output buffer
    private fun copyToOutput(samples: ShortArray, position: Int, numSamples: Int) {
        enlargeOutputBufferIfNeeded(numSamples)
        move(outputBuffer, numOutputSamples, samples, position, numSamples)
        numOutputSamples += numSamples
    }

    // Just copy from the input buffer to the output buffer.  Return num samples copied.
    private fun copyInputToOutput(position: Int): Int {
        var numSamples = remainingInputToCopy

        if (numSamples > maxRequired) {
            numSamples = maxRequired
        }
        copyToOutput(inputBuffer, position, numSamples)
        remainingInputToCopy -= numSamples
        return numSamples
    }

    // Read data out of the stream.  Sometimes no data will be available, and zero
    // is returned, which is not an error condition.
    fun readFloatFromStream(samples: FloatArray, maxSamples: Int): Int {
        var numSamples = numOutputSamples
        var remainingSamples = 0

        if (numSamples == 0) {
            return 0
        }
        if (numSamples > maxSamples) {
            remainingSamples = numSamples - maxSamples
            numSamples = maxSamples
        }
        for (xSample in 0 until numSamples * numChannels) {
            samples[xSample] = outputBuffer[xSample] / 32767.0f
        }
        move(outputBuffer, 0, outputBuffer, numSamples, remainingSamples)
        numOutputSamples = remainingSamples
        return numSamples
    }

    // Read short data out of the stream.  Sometimes no data will be available, and zero
    // is returned, which is not an error condition.
    fun readShortFromStream(samples: ShortArray, maxSamples: Int): Int {
        var numSamples = numOutputSamples
        var remainingSamples = 0

        if (numSamples == 0) {
            return 0
        }
        if (numSamples > maxSamples) {
            remainingSamples = numSamples - maxSamples
            numSamples = maxSamples
        }
        move(samples, 0, outputBuffer, 0, numSamples)
        move(outputBuffer, 0, outputBuffer, numSamples, remainingSamples)
        numOutputSamples = remainingSamples
        return numSamples
    }

    // Read unsigned byte data out of the stream.  Sometimes no data will be available, and zero
    // is returned, which is not an error condition.
    fun readUnsignedByteFromStream(samples: ByteArray, maxSamples: Int): Int {
        var numSamples = numOutputSamples
        var remainingSamples = 0

        if (numSamples == 0) {
            return 0
        }
        if (numSamples > maxSamples) {
            remainingSamples = numSamples - maxSamples
            numSamples = maxSamples
        }
        for (xSample in 0 until numSamples * numChannels) {
            samples[xSample] = ((outputBuffer[xSample].toInt() shr 8) + 128).toByte()
        }
        move(outputBuffer, 0, outputBuffer, numSamples, remainingSamples)
        numOutputSamples = remainingSamples
        return numSamples
    }

    // Read unsigned byte data out of the stream.  Sometimes no data will be available, and zero
    // is returned, which is not an error condition.
    fun readBytesFromStream(outBuffer: ByteArray, maxBytes: Int): Int {
        val maxSamples = maxBytes / (2 * numChannels)
        var numSamples = numOutputSamples
        var remainingSamples = 0

        if (numSamples == 0 || maxSamples == 0) {
            return 0
        }
        if (numSamples > maxSamples) {
            remainingSamples = numSamples - maxSamples
            numSamples = maxSamples
        }
        for (xSample in 0 until numSamples * numChannels) {
            val sample = outputBuffer[xSample]
            outBuffer[xSample shl 1] = (sample.toInt() and 0xff).toByte()
            outBuffer[(xSample shl 1) + 1] = (sample.toInt() shr 8).toByte()
        }
        move(outputBuffer, 0, outputBuffer, numSamples, remainingSamples)
        numOutputSamples = remainingSamples
        return 2 * numSamples * numChannels
    }

    // Force the sonic stream to generate output using whatever data it currently
    // has.  No extra delay will be added to the output, but flushing in the middle of
    // words could introduce distortion.
    fun flushStream() {
        val remainingSamples = numInputSamples
        val s = speed / pitch
        val r = rate * pitch
        val expectedOutputSamples =
            numOutputSamples + ((remainingSamples / s + numPitchSamples) / r + 0.5f).toInt()

        // Add enough silence to flush both input and pitch buffers.
        enlargeInputBufferIfNeeded(remainingSamples + 2 * maxRequired)
        for (xSample in 0 until 2 * maxRequired * numChannels) {
            inputBuffer[remainingSamples * numChannels + xSample] = 0
        }
        numInputSamples += 2 * maxRequired
        writeShortToStream(null, 0)
        // Throw away any extra samples we generated due to the silence we added.
        if (numOutputSamples > expectedOutputSamples) {
            numOutputSamples = expectedOutputSamples
        }
        // Empty input and pitch buffers.
        numInputSamples = 0
        remainingInputToCopy = 0
        numPitchSamples = 0
    }

    // Return the number of samples in the output buffer
    fun samplesAvailable(): Int = numOutputSamples

    // If skip is greater than one, average skip samples together and write them to
    // the down-sample buffer.  If numChannels is greater than one, mix the channels
    // together as we down sample.
    private fun downSampleInput(samples: ShortArray, position: Int, skip: Int) {
        val numSamples = maxRequired / skip
        val samplesPerValue = numChannels * skip
        var value: Int

        val pos = position * numChannels
        for (i in 0 until numSamples) {
            value = 0
            for (j in 0 until samplesPerValue) {
                value += samples[pos + i * samplesPerValue + j].toInt()
            }
            value /= samplesPerValue
            downSampleBuffer[i] = value.toShort()
        }
    }

    // Find the best frequency match in the range, and given a sample skip multiple.
    // For now, just find the pitch of the first channel.
    private fun findPitchPeriodInRange(
        samples: ShortArray,
        position: Int,
        minPeriod: Int,
        maxPeriod: Int
    ): Int {
        var bestPeriod = 0
        var worstPeriod = 255
        var minDiff = 1
        var maxDiff = 0

        val pos = position * numChannels
        for (period in minPeriod..maxPeriod) {
            var diff = 0
            for (i in 0 until period) {
                val sVal = samples[pos + i].toInt()
                val pVal = samples[pos + period + i].toInt()
                diff += if (sVal >= pVal) sVal - pVal else pVal - sVal
            }
            /* Note that the highest number of samples we add into diff will be less
               than 256, since we skip samples.  Thus, diff is a 24 bit number, and
               we can safely multiply by numSamples without overflow */
            if (diff * bestPeriod < minDiff * period) {
                minDiff = diff
                bestPeriod = period
            }
            if (diff * worstPeriod > maxDiff * period) {
                maxDiff = diff
                worstPeriod = period
            }
        }
        this.minDiff = minDiff / bestPeriod
        this.maxDiff = maxDiff / worstPeriod

        return bestPeriod
    }

    // At abrupt ends of voiced words, we can have pitch periods that are better
    // approximated by the previous pitch period estimate.  Try to detect this case.
    private fun prevPeriodBetter(minDiff: Int, maxDiff: Int, preferNewPeriod: Boolean): Boolean {
        if (minDiff == 0 || prevPeriod == 0) {
            return false
        }
        if (preferNewPeriod) {
            if (maxDiff > minDiff * 3) {
                // Got a reasonable match this period
                return false
            }
            if (minDiff * 2 <= prevMinDiff * 3) {
                // Mismatch is not that much greater this period
                return false
            }
        } else {
            if (minDiff <= prevMinDiff) {
                return false
            }
        }
        return true
    }

    // Find the pitch period.  This is a critical step, and we may have to try
    // multiple ways to get a good answer.  This version uses AMDF.  To improve
    // speed, we down sample by an integer factor get in the 11KHz range, and then
    // do it again with a narrower frequency range without down sampling
    private fun findPitchPeriod(samples: ShortArray, position: Int, preferNewPeriod: Boolean): Int {
        var period: Int
        val retPeriod: Int
        var skip = 1

        if (sampleRate > SONIC_AMDF_FREQ && quality == 0) {
            skip = sampleRate / SONIC_AMDF_FREQ
        }
        if (numChannels == 1 && skip == 1) {
            period = findPitchPeriodInRange(samples, position, minPeriod, maxPeriod)
        } else {
            downSampleInput(samples, position, skip)
            period = findPitchPeriodInRange(
                downSampleBuffer, 0, minPeriod / skip,
                maxPeriod / skip
            )
            if (skip != 1) {
                period *= skip
                var minP = period - (skip shl 2)
                var maxP = period + (skip shl 2)
                if (minP < minPeriod) {
                    minP = minPeriod
                }
                if (maxP > maxPeriod) {
                    maxP = maxPeriod
                }
                period = if (numChannels == 1) {
                    findPitchPeriodInRange(samples, position, minP, maxP)
                } else {
                    downSampleInput(samples, position, 1)
                    findPitchPeriodInRange(downSampleBuffer, 0, minP, maxP)
                }
            }
        }
        retPeriod = if (prevPeriodBetter(minDiff, maxDiff, preferNewPeriod)) {
            prevPeriod
        } else {
            period
        }
        prevMinDiff = minDiff
        prevPeriod = period
        return retPeriod
    }

    // Overlap two sound segments, ramp the volume of one down, while ramping the
    // other one from zero up, and add them, storing the result at the output.
    private fun overlapAdd(
        numSamples: Int,
        numChannels: Int,
        out: ShortArray,
        outPos: Int,
        rampDown: ShortArray,
        rampDownPos: Int,
        rampUp: ShortArray,
        rampUpPos: Int
    ) {
        for (i in 0 until numChannels) {
            var o = outPos * numChannels + i
            var u = rampUpPos * numChannels + i
            var d = rampDownPos * numChannels + i
            for (t in 0 until numSamples) {
                out[o] =
                    ((rampDown[d] * (numSamples - t) + rampUp[u] * t) / numSamples).toShort()
                o += numChannels
                d += numChannels
                u += numChannels
            }
        }
    }

    // Overlap two sound segments, ramp the volume of one down, while ramping the
    // other one from zero up, and add them, storing the result at the output.
    private fun overlapAddWithSeparation(
        numSamples: Int,
        numChannels: Int,
        separation: Int,
        out: ShortArray,
        outPos: Int,
        rampDown: ShortArray,
        rampDownPos: Int,
        rampUp: ShortArray,
        rampUpPos: Int
    ) {
        for (i in 0 until numChannels) {
            var o = outPos * numChannels + i
            var u = rampUpPos * numChannels + i
            var d = rampDownPos * numChannels + i
            for (t in 0 until numSamples + separation) {
                if (t < separation) {
                    out[o] = (rampDown[d] * (numSamples - t) / numSamples).toShort()
                    d += numChannels
                } else if (t < numSamples) {
                    out[o] =
                        ((rampDown[d] * (numSamples - t) + rampUp[u] * (t - separation)) / numSamples).toShort()
                    d += numChannels
                    u += numChannels
                } else {
                    out[o] = (rampUp[u] * (t - separation) / numSamples).toShort()
                    u += numChannels
                }
                o += numChannels
            }
        }
    }

    // Just move the new samples in the output buffer to the pitch buffer
    private fun moveNewSamplesToPitchBuffer(originalNumOutputSamples: Int) {
        val numSamples = numOutputSamples - originalNumOutputSamples

        if (numPitchSamples + numSamples > pitchBufferSize) {
            pitchBufferSize += (pitchBufferSize shr 1) + numSamples
            pitchBuffer = resize(pitchBuffer, pitchBufferSize)
        }
        move(pitchBuffer, numPitchSamples, outputBuffer, originalNumOutputSamples, numSamples)
        numOutputSamples = originalNumOutputSamples
        numPitchSamples += numSamples
    }

    // Remove processed samples from the pitch buffer.
    private fun removePitchSamples(numSamples: Int) {
        if (numSamples == 0) {
            return
        }
        move(pitchBuffer, 0, pitchBuffer, numSamples, numPitchSamples - numSamples)
        numPitchSamples -= numSamples
    }

    // Change the pitch.  The latency this introduces could be reduced by looking at
    // past samples to determine pitch, rather than future.
    private fun adjustPitch(originalNumOutputSamples: Int) {
        var period: Int
        var newPeriod: Int
        var separation: Int
        var position = 0

        if (numOutputSamples == originalNumOutputSamples) {
            return
        }
        moveNewSamplesToPitchBuffer(originalNumOutputSamples)
        while (numPitchSamples - position >= maxRequired) {
            period = findPitchPeriod(pitchBuffer, position, false)
            newPeriod = (period / pitch).toInt()
            enlargeOutputBufferIfNeeded(newPeriod)
            if (pitch >= 1.0f) {
                overlapAdd(
                    newPeriod, numChannels, outputBuffer, numOutputSamples, pitchBuffer,
                    position, pitchBuffer, position + period - newPeriod
                )
            } else {
                separation = newPeriod - period
                overlapAddWithSeparation(
                    period, numChannels, separation, outputBuffer, numOutputSamples,
                    pitchBuffer, position, pitchBuffer, position
                )
            }
            numOutputSamples += newPeriod
            position += period
        }
        removePitchSamples(position)
    }

    // Approximate the sinc function times a Hann window from the sinc table.
    private fun findSincCoefficient(i: Int, ratio: Int, width: Int): Int {
        val lobePoints = (SINC_TABLE_SIZE - 1) / SINC_FILTER_POINTS
        val left = i * lobePoints + (ratio * lobePoints) / width
        val right = left + 1
        val position = i * lobePoints * width + ratio * lobePoints - left * width
        val leftVal = sincTable[left].toInt()
        val rightVal = sincTable[right].toInt()

        return ((leftVal * (width - position) + rightVal * position) shl 1) / width
    }

    // Return 1 if value >= 0, else -1.  This represents the sign of value.
    private fun getSign(value: Int): Int = if (value >= 0) 1 else -1

    // Interpolate the new output sample.
    private fun interpolate(
        `in`: ShortArray,
        inPos: Int, // Index to first sample which already includes channel offset.
        oldSampleRate: Int,
        newSampleRate: Int
    ): Short {
        // Compute N-point sinc FIR-filter here.  Clip rather than overflow.
        var total = 0
        val position = newRatePosition * oldSampleRate
        val leftPosition = oldRatePosition * newSampleRate
        val rightPosition = (oldRatePosition + 1) * newSampleRate
        val ratio = rightPosition - position - 1
        val width = rightPosition - leftPosition
        var weight: Int
        var value: Int
        var oldSign: Int
        var overflowCount = 0

        for (i in 0 until SINC_FILTER_POINTS) {
            weight = findSincCoefficient(i, ratio, width)
            /* printf("%u %f\n", i, weight); */
            value = `in`[inPos + i * numChannels] * weight
            oldSign = getSign(total)
            total += value
            if (oldSign != getSign(total) && getSign(value) == oldSign) {
                /* We must have overflowed.  This can happen with a sinc filter. */
                overflowCount += oldSign
            }
        }
        /* It is better to clip than to wrap if there was a overflow. */
        if (overflowCount > 0) {
            return Short.MAX_VALUE
        } else if (overflowCount < 0) {
            return Short.MIN_VALUE
        }
        return (total shr 16).toShort()
    }

    // Change the rate.
    private fun adjustRate(rate: Float, originalNumOutputSamples: Int) {
        var newSampleRate = (sampleRate / rate).toInt()
        var oldSampleRate = sampleRate
        var position: Int
        val n = SINC_FILTER_POINTS

        // Set these values to help with the integer math
        while (newSampleRate > (1 shl 14) || oldSampleRate > (1 shl 14)) {
            newSampleRate = newSampleRate shr 1
            oldSampleRate = oldSampleRate shr 1
        }
        if (numOutputSamples == originalNumOutputSamples) {
            return
        }
        moveNewSamplesToPitchBuffer(originalNumOutputSamples)
        // Leave at least N pitch samples in the buffer
        position = 0
        while (position < numPitchSamples - n) {
            while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
                enlargeOutputBufferIfNeeded(1)
                for (i in 0 until numChannels) {
                    outputBuffer[numOutputSamples * numChannels + i] = interpolate(
                        pitchBuffer,
                        position * numChannels + i, oldSampleRate, newSampleRate
                    )
                }
                newRatePosition++
                numOutputSamples++
            }
            oldRatePosition++
            if (oldRatePosition == oldSampleRate) {
                oldRatePosition = 0
                if (newRatePosition != newSampleRate) {
                    System.out.printf("Assertion failed: newRatePosition != newSampleRate\n")
                    assert(false)
                }
                newRatePosition = 0
            }
            position++
        }
        removePitchSamples(position)
    }

    // Skip over a pitch period, and copy period/speed samples to the output
    private fun skipPitchPeriod(
        samples: ShortArray,
        position: Int,
        speed: Float,
        period: Int
    ): Int {
        val newSamples: Int

        if (speed >= 2.0f) {
            newSamples = (period / (speed - 1.0f)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = (period * (2.0f - speed) / (speed - 1.0f)).toInt()
        }
        enlargeOutputBufferIfNeeded(newSamples)
        overlapAdd(
            newSamples, numChannels, outputBuffer, numOutputSamples, samples, position,
            samples, position + period
        )
        numOutputSamples += newSamples
        return newSamples
    }

    // Insert a pitch period, and determine how much input to copy directly.
    private fun insertPitchPeriod(
        samples: ShortArray,
        position: Int,
        speed: Float,
        period: Int
    ): Int {
        val newSamples: Int

        if (speed < 0.5f) {
            newSamples = (period * speed / (1.0f - speed)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = (period * (2.0f * speed - 1.0f) / (1.0f - speed)).toInt()
        }
        enlargeOutputBufferIfNeeded(period + newSamples)
        move(outputBuffer, numOutputSamples, samples, position, period)
        overlapAdd(
            newSamples, numChannels, outputBuffer, numOutputSamples + period, samples,
            position + period, samples, position
        )
        numOutputSamples += period + newSamples
        return newSamples
    }

    // Resample as many pitch periods as we have buffered on the input.  Return 0 if
    // we fail to resize an input or output buffer.  Also scale the output by the volume.
    private fun changeSpeed(speed: Float) {
        val numSamples = numInputSamples
        var position = 0
        var period: Int
        var newSamples: Int

        if (numInputSamples < maxRequired) {
            return
        }
        do {
            if (remainingInputToCopy > 0) {
                newSamples = copyInputToOutput(position)
                position += newSamples
            } else {
                period = findPitchPeriod(inputBuffer, position, true)
                if (speed > 1.0) {
                    newSamples = skipPitchPeriod(inputBuffer, position, speed, period)
                    position += period + newSamples
                } else {
                    newSamples = insertPitchPeriod(inputBuffer, position, speed, period)
                    position += newSamples
                }
            }
        } while (position + maxRequired <= numSamples)
        removeInputSamples(position)
    }

    // Resample as many pitch periods as we have buffered on the input.  Scale the output by the volume.
    private fun processStreamInput() {
        val originalNumOutputSamples = numOutputSamples
        val s = speed / pitch
        var r = rate

        if (!useChordPitch) {
            r *= pitch
        }
        if (s > 1.00001 || s < 0.99999) {
            changeSpeed(s)
        } else {
            copyToOutput(inputBuffer, 0, numInputSamples)
            numInputSamples = 0
        }
        if (useChordPitch) {
            if (pitch != 1.0f) {
                adjustPitch(originalNumOutputSamples)
            }
        } else if (r != 1.0f) {
            adjustRate(r, originalNumOutputSamples)
        }
        if (volume != 1.0f) {
            // Adjust output volume.
            scaleSamples(
                outputBuffer, originalNumOutputSamples, numOutputSamples - originalNumOutputSamples,
                volume
            )
        }
    }

    // Write floating point data to the input buffer and process it.
    fun writeFloatToStream(samples: FloatArray, numSamples: Int) {
        addFloatSamplesToInputBuffer(samples, numSamples)
        processStreamInput()
    }

    // Write the data to the input stream, and process it.
    fun writeShortToStream(samples: ShortArray?, numSamples: Int) {
        addShortSamplesToInputBuffer(samples, numSamples)
        processStreamInput()
    }

    // Simple wrapper around sonicWriteFloatToStream that does the unsigned byte to short
    // conversion for you.
    fun writeUnsignedByteToStream(samples: ByteArray, numSamples: Int) {
        addUnsignedByteSamplesToInputBuffer(samples, numSamples)
        processStreamInput()
    }

    // Simple wrapper around sonicWriteBytesToStream that does the byte to 16-bit LE conversion.
    fun writeBytesToStream(inBuffer: ByteArray, numBytes: Int) {
        addBytesToInputBuffer(inBuffer, numBytes)
        processStreamInput()
    }

    /* This is a non-stream oriented interface to just change the speed of a sound sample */
    fun sonicChangeShortSpeed(
        samples: ShortArray,
        numSamples: Int,
        speed: Float,
        pitch: Float,
        rate: Float,
        volume: Float,
        useChordPitch: Boolean,
        sampleRate: Int,
        numChannels: Int
    ): Int {
        var numSamples = numSamples
        val stream = Sonic(sampleRate, numChannels)

        stream.setSpeed(speed)
        stream.setPitch(pitch)
        stream.setRate(rate)
        stream.setVolume(volume)
        stream.setChordPitch(useChordPitch)
        stream.writeShortToStream(samples, numSamples)
        stream.flushStream()
        numSamples = stream.samplesAvailable()
        stream.readShortFromStream(samples, numSamples)
        return numSamples
    }

    companion object {

        private const val SONIC_MIN_PITCH = 65
        private const val SONIC_MAX_PITCH = 400

        // This is used to down-sample some inputs to improve speed
        private const val SONIC_AMDF_FREQ = 4000

        // The number of points to use in the sinc FIR filter for resampling.
        private const val SINC_FILTER_POINTS = 12
        private const val SINC_TABLE_SIZE = 601

        // Lookup table for windowed sinc function of SINC_FILTER_POINTS points.
        // The code to generate this is in the header comment of sonic.c.
        private val sincTable = shortArrayOf(
            0, 0, 0, 0, 0, 0, 0, -1, -1, -2, -2, -3, -4, -6,
            -7, -9, -10, -12, -14, -17, -19, -21, -24, -26, -29, -32, -34, -37,
            -40, -42, -44, -47, -48, -50, -51, -52, -53, -53, -53, -52, -50, -48,
            -46, -43, -39, -34, -29, -22, -16, -8, 0, 9, 19, 29, 41, 53,
            65, 79, 92, 107, 121, 137, 152, 168, 184, 200, 215, 231, 247, 262,
            276, 291, 304, 317, 328, 339, 348, 357, 363, 369, 372, 374, 375, 373,
            369, 363, 355, 345, 332, 318, 300, 281, 259, 234, 208, 178, 147, 113,
            77, 39, 0, -41, -85, -130, -177, -225, -274, -324, -375, -426, -478, -530,
            -581, -632, -682, -731, -779, -825, -870, -912, -951, -989, -1023, -1053, -1080, -1104,
            -1123, -1138, -1149, -1154, -1155, -1151, -1141, -1125, -1105, -1078, -1046, -1007, -963, -913,
            -857, -796, -728, -655, -576, -492, -403, -309, -210, -107, 0, 111, 225, 342,
            462, 584, 708, 833, 958, 1084, 1209, 1333, 1455, 1575, 1693, 1807, 1916, 2022,
            2122, 2216, 2304, 2384, 2457, 2522, 2579, 2625, 2663, 2689, 2706, 2711, 2705, 2687,
            2657, 2614, 2559, 2491, 2411, 2317, 2211, 2092, 1960, 1815, 1658, 1489, 1308, 1115,
            912, 698, 474, 241, 0, -249, -506, -769, -1037, -1310, -1586, -1864, -2144, -2424,
            -2703, -2980, -3254, -3523, -3787, -4043, -4291, -4529, -4757, -4972, -5174, -5360, -5531, -5685,
            -5819, -5935, -6029, -6101, -6150, -6175, -6175, -6149, -6096, -6015, -5905, -5767, -5599, -5401,
            -5172, -4912, -4621, -4298, -3944, -3558, -3141, -2693, -2214, -1705, -1166, -597, 0, 625,
            1277, 1955, 2658, 3386, 4135, 4906, 5697, 6506, 7332, 8173, 9027, 9893, 10769, 11654,
            12544, 13439, 14335, 15232, 16128, 17019, 17904, 18782, 19649, 20504, 21345, 22170, 22977, 23763,
            24527, 25268, 25982, 26669, 27327, 27953, 28547, 29107, 29632, 30119, 30569, 30979, 31349, 31678,
            31964, 32208, 32408, 32565, 32677, 32744, 32767, 32744, 32677, 32565, 32408, 32208, 31964, 31678,
            31349, 30979, 30569, 30119, 29632, 29107, 28547, 27953, 27327, 26669, 25982, 25268, 24527, 23763,
            22977, 22170, 21345, 20504, 19649, 18782, 17904, 17019, 16128, 15232, 14335, 13439, 12544, 11654,
            10769, 9893, 9027, 8173, 7332, 6506, 5697, 4906, 4135, 3386, 2658, 1955, 1277, 625,
            0, -597, -1166, -1705, -2214, -2693, -3141, -3558, -3944, -4298, -4621, -4912, -5172, -5401,
            -5599, -5767, -5905, -6015, -6096, -6149, -6175, -6175, -6150, -6101, -6029, -5935, -5819, -5685,
            -5531, -5360, -5174, -4972, -4757, -4529, -4291, -4043, -3787, -3523, -3254, -2980, -2703, -2424,
            -2144, -1864, -1586, -1310, -1037, -769, -506, -249, 0, 241, 474, 698, 912, 1115,
            1308, 1489, 1658, 1815, 1960, 2092, 2211, 2317, 2411, 2491, 2559, 2614, 2657, 2687,
            2705, 2711, 2706, 2689, 2663, 2625, 2579, 2522, 2457, 2384, 2304, 2216, 2122, 2022,
            1916, 1807, 1693, 1575, 1455, 1333, 1209, 1084, 958, 833, 708, 584, 462, 342,
            225, 111, 0, -107, -210, -309, -403, -492, -576, -655, -728, -796, -857, -913,
            -963, -1007, -1046, -1078, -1105, -1125, -1141, -1151, -1155, -1154, -1149, -1138, -1123, -1104,
            -1080, -1053, -1023, -989, -951, -912, -870, -825, -779, -731, -682, -632, -581, -530,
            -478, -426, -375, -324, -274, -225, -177, -130, -85, -41, 0, 39, 77, 113,
            147, 178, 208, 234, 259, 281, 300, 318, 332, 345, 355, 363, 369, 373,
            375, 374, 372, 369, 363, 357, 348, 339, 328, 317, 304, 291, 276, 262,
            247, 231, 215, 200, 184, 168, 152, 137, 121, 107, 92, 79, 65, 53,
            41, 29, 19, 9, 0, -8, -16, -22, -29, -34, -39, -43, -46, -48,
            -50, -52, -53, -53, -53, -52, -51, -50, -48, -47, -44, -42, -40, -37,
            -34, -32, -29, -26, -24, -21, -19, -17, -14, -12, -10, -9, -7, -6,
            -4, -3, -2, -2, -1, -1, 0, 0, 0, 0, 0, 0, 0
        )

        // This is a non-stream oriented interface to just change the speed of a sound sample
        @JvmStatic
        fun changeFloatSpeed(
            samples: FloatArray,
            numSamples: Int,
            speed: Float,
            pitch: Float,
            rate: Float,
            volume: Float,
            useChordPitch: Boolean,
            sampleRate: Int,
            numChannels: Int
        ): Int {
            var numSamples = numSamples
            val stream = Sonic(sampleRate, numChannels)

            stream.setSpeed(speed)
            stream.setPitch(pitch)
            stream.setRate(rate)
            stream.setVolume(volume)
            stream.setChordPitch(useChordPitch)
            stream.writeFloatToStream(samples, numSamples)
            stream.flushStream()
            numSamples = stream.samplesAvailable()
            stream.readFloatFromStream(samples, numSamples)
            return numSamples
        }
    }
}
