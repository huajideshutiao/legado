package io.legado.app.model.analyzeRule

object RuleCombiner {

    fun <T> combineResults(
        results: List<List<T>>,
        elementsType: String,
        target: MutableList<T>
    ) {
        if (results.isEmpty()) return
        when (elementsType) {
            "%%" -> {
                for (i in results[0].indices) {
                    for (temp in results) {
                        if (i < temp.size) {
                            target.add(temp[i])
                        }
                    }
                }
            }

            else -> {
                for (temp in results) {
                    target.addAll(temp)
                }
            }
        }
    }

    fun <T> combineNullableResults(
        results: List<List<T?>>,
        elementsType: String,
        target: MutableList<T>
    ) {
        if (results.isEmpty()) return
        when (elementsType) {
            "%%" -> {
                for (i in results[0].indices) {
                    for (temp in results) {
                        if (i < temp.size) {
                            temp[i]?.let { target.add(it) }
                        }
                    }
                }
            }

            else -> {
                for (temp in results) {
                    @Suppress("UNCHECKED_CAST")
                    target.addAll(temp as List<T>)
                }
            }
        }
    }

}
