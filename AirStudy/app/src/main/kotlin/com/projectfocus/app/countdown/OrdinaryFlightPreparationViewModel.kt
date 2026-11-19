package com.projectfocus.app.countdown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Retains an unsubmitted route across Activity recreation, never across a new process. */
@HiltViewModel
class OrdinaryFlightPreparationViewModel @Inject constructor(
    actions: SessionOrdinaryFlightPreparationActions,
) : ViewModel() {
    val coordinator = OrdinaryFlightPreparationCoordinator(actions, viewModelScope)
}
