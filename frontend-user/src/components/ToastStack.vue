<script setup>
import { toasts } from '../ui'
</script>

<template>
  <div class="toast-stack">
    <TransitionGroup name="toast">
      <div
        v-for="item in toasts"
        :key="item.id"
        class="toast-item"
        :class="`toast-${item.type}`"
      >
        <span class="toast-mark">{{ item.type === 'error' ? '!' : '✓' }}</span>
        <span>{{ item.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>
