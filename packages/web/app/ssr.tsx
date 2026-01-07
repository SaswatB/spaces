/// <reference types="vinxi/types/server" />
import {
  createStartHandler,
  defaultStreamHandler,
} from '@tanstack/start/server';
import { getRouterManifest } from '@tanstack/start/router-manifest';
import { createRouter } from './router';

// TanStack Start server handler - types vary by version
const handler = (createStartHandler as Function)({
  createRouter,
  getRouterManifest,
});

export default handler(defaultStreamHandler);
