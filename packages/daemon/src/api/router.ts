import { initTRPC, TRPCError } from '@trpc/server';
import { z } from 'zod';
import type { SpacesService } from '../services/spaces.js';
import {
  CreateEntrypointInputSchema,
  CreateLayerInputSchema,
  CreateUserMountInputSchema,
  AttachLayerInputSchema,
} from '../types.js';

/**
 * Context passed to all tRPC procedures
 */
export interface Context {
  spacesService: SpacesService;
  authToken: string | string[] | null;
  expectedAuthToken: string | null;
}

const t = initTRPC.context<Context>().create();

export const router = t.router;
const authMiddleware = t.middleware(({ ctx, next }) => {
  if (!ctx.expectedAuthToken) {
    return next();
  }
  let token =
    typeof ctx.authToken === 'string'
      ? ctx.authToken
      : ctx.authToken?.[0] ?? null;
  if (token?.startsWith('Bearer ')) {
    token = token.slice('Bearer '.length).trim();
  }
  if (!token || token !== ctx.expectedAuthToken) {
    throw new TRPCError({ code: 'UNAUTHORIZED' });
  }
  return next();
});

export const publicProcedure = t.procedure.use(authMiddleware);

/**
 * Main API router
 */
export const appRouter = router({
  // ===========================================================================
  // Entrypoints
  // ===========================================================================

  entrypoints: router({
    create: publicProcedure
      .input(CreateEntrypointInputSchema)
      .mutation(async ({ ctx, input }) => {
        return ctx.spacesService.createEntrypoint(input);
      }),

    get: publicProcedure
      .input(z.object({ id: z.string() }))
      .query(async ({ ctx, input }) => {
        return ctx.spacesService.getEntrypoint(input.id);
      }),

    list: publicProcedure.query(async ({ ctx }) => {
      return ctx.spacesService.listEntrypoints();
    }),

    delete: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        await ctx.spacesService.deleteEntrypoint(input.id);
        return { success: true };
      }),
  }),

  // ===========================================================================
  // Layers
  // ===========================================================================

  layers: router({
    create: publicProcedure
      .input(CreateLayerInputSchema)
      .mutation(async ({ ctx, input }) => {
        return ctx.spacesService.createLayer(input);
      }),

    get: publicProcedure
      .input(z.object({ id: z.string() }))
      .query(async ({ ctx, input }) => {
        return ctx.spacesService.getLayerWithStatus(input.id);
      }),

    list: publicProcedure
      .input(z.object({ entrypointId: z.string().optional() }).optional())
      .query(async ({ ctx, input }) => {
        return ctx.spacesService.listLayersWithStatus(input?.entrypointId);
      }),

    delete: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        await ctx.spacesService.deleteLayer(input.id);
        return { success: true };
      }),

    mount: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        const layer = await ctx.spacesService.getLayer(input.id);
        if (!layer) {
          throw new Error(`Layer not found: ${input.id}`);
        }
        await ctx.spacesService.mountLayer(layer);
        return { success: true };
      }),

    unmount: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        const layer = await ctx.spacesService.getLayer(input.id);
        if (!layer) {
          throw new Error(`Layer not found: ${input.id}`);
        }
        await ctx.spacesService.unmountLayer(layer);
        return { success: true };
      }),

    openTerminal: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        const layer = await ctx.spacesService.getLayer(input.id);
        if (!layer) {
          throw new Error(`Layer not found: ${input.id}`);
        }
        await ctx.spacesService.openTerminal(layer.mountPath);
        return { success: true };
      }),
  }),

  // ===========================================================================
  // User Mounts
  // ===========================================================================

  userMounts: router({
    create: publicProcedure
      .input(CreateUserMountInputSchema)
      .mutation(async ({ ctx, input }) => {
        return ctx.spacesService.createUserMount(input);
      }),

    get: publicProcedure
      .input(z.object({ id: z.string() }))
      .query(async ({ ctx, input }) => {
        return ctx.spacesService.getUserMountWithStatus(input.id);
      }),

    list: publicProcedure
      .input(z.object({ entrypointId: z.string().optional() }).optional())
      .query(async ({ ctx, input }) => {
        return ctx.spacesService.listUserMountsWithStatus(input?.entrypointId);
      }),

    delete: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        await ctx.spacesService.deleteUserMount(input.id);
        return { success: true };
      }),

    attachLayer: publicProcedure
      .input(AttachLayerInputSchema)
      .mutation(async ({ ctx, input }) => {
        await ctx.spacesService.attachLayer(input.userMountId, input.layerId);
        return { success: true };
      }),

    mount: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        const userMount = await ctx.spacesService.getUserMount(input.id);
        if (!userMount) {
          throw new Error(`User mount not found: ${input.id}`);
        }
        await ctx.spacesService.mountUserMount(userMount);
        return { success: true };
      }),

    unmount: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        const userMount = await ctx.spacesService.getUserMount(input.id);
        if (!userMount) {
          throw new Error(`User mount not found: ${input.id}`);
        }
        await ctx.spacesService.unmountUserMount(userMount);
        return { success: true };
      }),

    openTerminal: publicProcedure
      .input(z.object({ id: z.string() }))
      .mutation(async ({ ctx, input }) => {
        const userMount = await ctx.spacesService.getUserMount(input.id);
        if (!userMount) {
          throw new Error(`User mount not found: ${input.id}`);
        }
        await ctx.spacesService.openTerminal(userMount.mountPath);
        return { success: true };
      }),
  }),

  // ===========================================================================
  // System
  // ===========================================================================

  system: router({
    status: publicProcedure.query(async ({ ctx }) => {
      const entrypoints = await ctx.spacesService.listEntrypoints();
      const layers = await ctx.spacesService.listLayersWithStatus();
      const userMounts = await ctx.spacesService.listUserMountsWithStatus();

      return {
        entrypointCount: entrypoints.length,
        layerCount: layers.length,
        userMountCount: userMounts.length,
        mountedLayers: layers.filter((l) => l.mountStatus === 'mounted').length,
        mountedUserMounts: userMounts.filter((m) => m.mountStatus === 'mounted')
          .length,
      };
    }),

    remountAll: publicProcedure.mutation(async ({ ctx }) => {
      await ctx.spacesService.remountAll();
      return { success: true };
    }),
  }),
});

export type AppRouter = typeof appRouter;
