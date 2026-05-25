package io.legado.app.ui.association

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub

class RuleSubViewModel(app: Application) : BaseViewModel(app) {

    fun save(ruleSub: RuleSub) {
        execute {
            if (ruleSub.customOrder == 0) {
                ruleSub.customOrder =
                    (appDb.ruleSubDao.all.maxOfOrNull { it.customOrder } ?: 0) + 1
            }
            appDb.ruleSubDao.insert(ruleSub)
        }
    }

    fun delete(ruleSub: RuleSub) {
        execute {
            appDb.ruleSubDao.delete(ruleSub)
        }
    }

    fun upOrder(items: List<RuleSub>) {
        execute {
            val array = items.mapIndexed { index, ruleSub ->
                ruleSub.copy(customOrder = index + 1)
            }.toTypedArray()
            appDb.ruleSubDao.update(*array)
        }
    }

    fun toTop(ruleSub: RuleSub) {
        execute {
            val minOrder = (appDb.ruleSubDao.all.minOfOrNull { it.customOrder } ?: 0) - 1
            ruleSub.customOrder = minOrder
            appDb.ruleSubDao.update(ruleSub)
        }
    }

    fun toBottom(ruleSub: RuleSub) {
        execute {
            val maxOrder = (appDb.ruleSubDao.all.maxOfOrNull { it.customOrder } ?: 0) + 1
            ruleSub.customOrder = maxOrder
            appDb.ruleSubDao.update(ruleSub)
        }
    }

}
